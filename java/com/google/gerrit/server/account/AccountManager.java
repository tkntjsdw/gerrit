// Copyright (C) 2009 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.server.account;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.gerrit.server.account.externalids.ExternalId.SCHEME_GERRIT;
import static com.google.gerrit.server.account.externalids.ExternalId.SCHEME_GOOGLE_OAUTH;
import static com.google.gerrit.server.account.externalids.ExternalId.SCHEME_USERNAME;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.flogger.FluentLogger;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.gerrit.common.data.GlobalCapability;
import com.google.gerrit.entities.AccessSection;
import com.google.gerrit.entities.Account;
import com.google.gerrit.entities.AccountGroup;
import com.google.gerrit.entities.Permission;
import com.google.gerrit.exceptions.NoSuchGroupException;
import com.google.gerrit.exceptions.StorageException;
import com.google.gerrit.extensions.client.AccountFieldName;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.Sequences;
import com.google.gerrit.server.ServerInitiated;
import com.google.gerrit.server.account.AccountsUpdate.ConfigureStatelessDelta;
import com.google.gerrit.server.account.externalids.DuplicateExternalIdKeyException;
import com.google.gerrit.server.account.externalids.ExternalId;
import com.google.gerrit.server.account.externalids.ExternalIdFactory;
import com.google.gerrit.server.account.externalids.ExternalIdKeyFactory;
import com.google.gerrit.server.account.externalids.ExternalIds;
import com.google.gerrit.server.auth.NoSuchUserException;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.gerrit.server.group.db.GroupDelta;
import com.google.gerrit.server.group.db.GroupsUpdate;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.ssh.SshKeyCache;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Config;

/** Tracks authentication related details for user accounts. */
@Singleton
public class AccountManager {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final Sequences sequences;
  private final Accounts accounts;
  private final Provider<AccountsUpdate> accountsUpdateProvider;
  private final AccountCache byIdCache;
  private final Realm realm;
  private final IdentifiedUser.GenericFactory userFactory;
  private final SshKeyCache sshKeyCache;
  private final ProjectCache projectCache;
  private final AtomicBoolean awaitsFirstAccountCheck;
  private final ExternalIds externalIds;
  private final GroupsUpdate.Factory groupsUpdateFactory;
  private final boolean autoUpdateAccountActiveStatus;
  private final SetInactiveFlag setInactiveFlag;
  private final ExternalIdFactory externalIdFactory;
  private final ExternalIdKeyFactory externalIdKeyFactory;

  @VisibleForTesting
  @Inject
  public AccountManager(
      Sequences sequences,
      @GerritServerConfig Config cfg,
      Accounts accounts,
      @ServerInitiated Provider<AccountsUpdate> accountsUpdateProvider,
      AccountCache byIdCache,
      Realm accountMapper,
      IdentifiedUser.GenericFactory userFactory,
      SshKeyCache sshKeyCache,
      ProjectCache projectCache,
      ExternalIds externalIds,
      GroupsUpdate.Factory groupsUpdateFactory,
      SetInactiveFlag setInactiveFlag,
      ExternalIdFactory externalIdFactory,
      ExternalIdKeyFactory externalIdKeyFactory) {
    this.sequences = sequences;
    this.accounts = accounts;
    this.accountsUpdateProvider = accountsUpdateProvider;
    this.byIdCache = byIdCache;
    this.realm = accountMapper;
    this.userFactory = userFactory;
    this.sshKeyCache = sshKeyCache;
    this.projectCache = projectCache;
    this.awaitsFirstAccountCheck =
        new AtomicBoolean(cfg.getBoolean("capability", "makeFirstUserAdmin", true));
    this.externalIds = externalIds;
    this.groupsUpdateFactory = groupsUpdateFactory;
    this.autoUpdateAccountActiveStatus =
        cfg.getBoolean("auth", "autoUpdateAccountActiveStatus", false);
    this.setInactiveFlag = setInactiveFlag;
    this.externalIdFactory = externalIdFactory;
    this.externalIdKeyFactory = externalIdKeyFactory;
  }

  /** Returns a user identified by this external identity string */
  public Optional<Account.Id> lookup(String externalId) throws AccountException {
    try {
      return externalIds.get(externalIdKeyFactory.parse(externalId)).map(ExternalId::accountId);
    } catch (IOException e) {
      throw new AccountException("Cannot lookup account " + externalId, e);
    }
  }

  /**
   * Authenticate the user, potentially creating a new account if they are new.
   *
   * @param who identity of the user, with any details we received about them.
   * @return the result of authenticating the user.
   * @throws com.google.gerrit.server.account.AccountException the account does not exist, and
   *     cannot be created, or exists, but cannot be located, is unable to be activated or
   *     deactivated, or is inactive, or cannot be added to the admin group (only for the first
   *     account).
   */
  @CanIgnoreReturnValue
  public AuthResult authenticate(AuthRequest who) throws AccountException, IOException {
    try {
      who = realm.authenticate(who);
    } catch (NoSuchUserException e) {
      deactivateAccountIfItExists(who);
      throw e;
    }
    try {
      Optional<ExternalId> optionalExtId = externalIds.get(who.getExternalIdKey());
      if (!optionalExtId.isPresent()) {
        return createOrLinkAccount(who);
      }

      ExternalId extId = optionalExtId.get();
      Optional<AccountState> accountState = byIdCache.get(extId.accountId());
      if (!accountState.isPresent()) {
        logger.atSevere().log(
            "Authentication with external ID %s failed. Account %s doesn't exist.",
            extId.key().get(), extId.accountId().get());
        throw new AccountException("Authentication error, account not found");
      }

      // Account exists
      Optional<Account> act = updateAccountActiveStatus(who, accountState.get().account());
      if (!act.isPresent()) {
        // The account was deleted since we checked for it last time. This should never happen
        // since we don't support deletion of accounts.
        throw new AccountException("Authentication error, account not found");
      }
      if (!act.get().isActive()) {
        throw new AccountException("Authentication error, account inactive");
      }

      // return the identity to the caller.
      update(who, extId);
      return new AuthResult(extId.accountId(), who.getExternalIdKey(), false);
    } catch (StorageException | ConfigInvalidException e) {
      throw new AccountException("Authentication error", e);
    }
  }

  /**
   * Determines if a new account should be created or if we should link to an existing account.
   *
   * @param who identity of the user, with any details we received about them.
   * @return the result of authenticating the user.
   * @throws com.google.gerrit.server.account.AccountException the account does not exist, and
   *     cannot be created, or exists, but cannot be located, is unable to be activated or
   *     deactivated, or is inactive, or cannot be added to the admin group (only for the first
   *     account).
   */
  private AuthResult createOrLinkAccount(AuthRequest who)
      throws AccountException, IOException, ConfigInvalidException {
    // TODO: in case of extension of further migration paths this code should
    // probably be refactored out by creating an AccountMigrator extension point.
    if (who.getExternalIdKey().isScheme(SCHEME_GOOGLE_OAUTH)) {
      Optional<ExternalId> existingLDAPExtID = findLdapExternalId(who);
      if (existingLDAPExtID.isPresent()) {
        return migrateLdapAccountToOauth(who, existingLDAPExtID.get());
      }
    }
    logger.atFine().log(
        "External ID for account %s not found. A new account will be automatically created.",
        who.getEmailAddress());
    return create(who);
  }

  private AuthResult migrateLdapAccountToOauth(AuthRequest who, ExternalId ldapExternalId)
      throws AccountException, IOException, ConfigInvalidException {
    Account.Id extAccId = ldapExternalId.accountId();
    AuthResult res = link(extAccId, who);
    accountsUpdateProvider
        .get()
        .updateForUserManagementRequests(
            "remove existing LDAP externalId with matching e-mail",
            extAccId,
            u -> {
              u.deleteExternalId(ldapExternalId);
            });
    return res;
  }

  private void deactivateAccountIfItExists(AuthRequest authRequest) {
    if (!shouldUpdateActiveStatus(authRequest)) {
      return;
    }
    try {
      Optional<ExternalId> extId = externalIds.get(authRequest.getExternalIdKey());
      if (!extId.isPresent()) {
        return;
      }
      setInactiveFlag.deactivate(extId.get().accountId());
    } catch (Exception e) {
      logger.atSevere().withCause(e).log(
          "Unable to deactivate account %s",
          authRequest
              .getUserName()
              .orElseGet(() -> " for external ID key " + authRequest.getExternalIdKey().get()));
    }
  }

  private Optional<Account> updateAccountActiveStatus(AuthRequest authRequest, Account account)
      throws AccountException {
    if (!shouldUpdateActiveStatus(authRequest) || authRequest.isActive() == account.isActive()) {
      return Optional.of(account);
    }

    if (authRequest.isActive()) {
      try {
        setInactiveFlag.activate(account.id());
      } catch (Exception e) {
        throw new AccountException("Unable to activate account " + account.id(), e);
      }
    } else {
      try {
        setInactiveFlag.deactivate(account.id());
      } catch (Exception e) {
        throw new AccountException("Unable to deactivate account " + account.id(), e);
      }
    }
    return byIdCache.get(account.id()).map(AccountState::account);
  }

  private boolean shouldUpdateActiveStatus(AuthRequest authRequest) {
    return autoUpdateAccountActiveStatus && authRequest.authProvidesAccountActiveStatus();
  }

  private void update(AuthRequest who, ExternalId extId)
      throws IOException, ConfigInvalidException, AccountException {
    IdentifiedUser user = userFactory.create(extId.accountId());
    List<ConfigureStatelessDelta> accountUpdates = new ArrayList<>();

    // If the email address was modified by the authentication provider,
    // update our records to match the changed email.
    //
    String newEmail = who.getEmailAddress();
    String oldEmail = extId.email();
    if (newEmail != null && !newEmail.equals(oldEmail)) {
      ExternalId extIdWithNewEmail =
          externalIdFactory.createWithPassword(
              extId.key(), extId.accountId(), newEmail, extId.password());
      checkEmailNotUsed(extId.accountId(), extIdWithNewEmail);
      accountUpdates.add(u -> u.replaceExternalId(extId, extIdWithNewEmail));

      if (oldEmail != null && oldEmail.equals(user.getAccount().preferredEmail())) {
        accountUpdates.add(u -> u.setPreferredEmail(newEmail));
      }
    }

    if (!Strings.isNullOrEmpty(who.getDisplayName())
        && !Objects.equals(user.getAccount().fullName(), who.getDisplayName())) {
      accountUpdates.add(a -> a.setFullName(who.getDisplayName()));
    }

    if (!realm.allowsEdit(AccountFieldName.USER_NAME)
        && who.getUserName().isPresent()
        && !who.getUserName().equals(user.getUserName())) {
      if (user.getUserName().isPresent()) {
        logger.atWarning().log(
            "Not changing already set username %s to %s",
            user.getUserName().get(), who.getUserName().get());
      } else {
        logger.atWarning().log("Not setting username to %s", who.getUserName().get());
      }
    }

    if (!accountUpdates.isEmpty()) {
      Optional<AccountState> updatedAccount =
          accountsUpdateProvider
              .get()
              .update(
                  "Update Account on Login",
                  user.getAccountId(),
                  AccountsUpdate.joinDeltaConfigures(accountUpdates));
      if (!updatedAccount.isPresent()) {
        throw new StorageException("Account " + user.getAccountId() + " has been deleted");
      }
    }
  }

  private Optional<ExternalId> findLdapExternalId(AuthRequest who) throws IOException {
    String email = who.getEmailAddress();
    if (email == null || email.isEmpty()) {
      return Optional.empty();
    }

    Optional<ExternalId> ldapExternalId =
        externalIds.byEmail(email).stream().filter(a -> a.isScheme(SCHEME_GERRIT)).findFirst();
    return ldapExternalId;
  }

  private AuthResult create(AuthRequest who)
      throws AccountException, IOException, ConfigInvalidException {
    Account.Id newId = Account.id(sequences.nextAccountId());
    logger.atFine().log("Assigning new Id %s to account", newId);

    ExternalId extId =
        externalIdFactory.createWithEmail(who.getExternalIdKey(), newId, who.getEmailAddress());
    logger.atFine().log("Created external Id: %s", extId);
    checkEmailNotUsed(newId, extId);
    ExternalId userNameExtId =
        who.getUserName().isPresent() ? createUsername(newId, who.getUserName().get()) : null;

    boolean isFirstAccount = awaitsFirstAccountCheck.getAndSet(false) && !accounts.hasAnyAccount();

    AccountState accountState;
    try {
      accountState =
          accountsUpdateProvider
              .get()
              .insert(
                  "Create Account on First Login",
                  newId,
                  u -> {
                    u.setFullName(who.getDisplayName())
                        .setPreferredEmail(extId.email())
                        .addExternalId(extId);
                    if (userNameExtId != null) {
                      u.addExternalId(userNameExtId);
                    }
                  });
    } catch (DuplicateExternalIdKeyException e) {
      throw new AccountException(
          "Cannot assign external ID \""
              + e.getDuplicateKey().get()
              + "\" to account "
              + newId
              + "; external ID already in use.",
          e);
    } finally {
      // If adding the account failed, it may be that it actually was the
      // first account. So we reset the 'check for first account'-guard, as
      // otherwise the first account would not get administration permissions.
      awaitsFirstAccountCheck.set(isFirstAccount);
    }

    if (userNameExtId != null) {
      who.getUserName().ifPresent(sshKeyCache::evict);
    }

    IdentifiedUser user = userFactory.create(newId);

    if (isFirstAccount) {
      // This is the first user account on our site. Assume this user
      // is going to be the site's administrator and just make them that
      // to bootstrap the authentication database.
      //
      Permission admin =
          projectCache
              .getAllProjects()
              .getConfig()
              .getAccessSection(AccessSection.GLOBAL_CAPABILITIES)
              .orElseThrow(() -> new IllegalStateException("access section does not exist"))
              .getPermission(GlobalCapability.ADMINISTRATE_SERVER);

      AccountGroup.UUID adminGroupUuid = admin.getRules().get(0).getGroup().getUUID();
      addGroupMember(adminGroupUuid, user);
    }

    realm.onCreateAccount(who, accountState.account());
    return new AuthResult(newId, extId.key(), true);
  }

  private ExternalId createUsername(Account.Id accountId, String username)
      throws AccountUserNameException {
    checkArgument(!Strings.isNullOrEmpty(username));

    if (!ExternalId.isValidUsername(username)) {
      throw new AccountUserNameException(
          String.format(
              "Cannot assign user name \"%s\" to account %s; name does not conform.",
              username, accountId));
    }
    return externalIdFactory.create(SCHEME_USERNAME, username, accountId);
  }

  private void checkEmailNotUsed(Account.Id accountId, ExternalId extIdToBeCreated)
      throws IOException, AccountException {
    String email = extIdToBeCreated.email();
    if (email == null) {
      return;
    }

    ImmutableSet<ExternalId> existingExtIdsWithEmail = externalIds.byEmail(email);
    if (existingExtIdsWithEmail.isEmpty()) {
      return;
    }

    for (ExternalId externalId : existingExtIdsWithEmail) {
      if (externalId.accountId().get() != accountId.get()) {
        logger.atWarning().log(
            "Email %s is already assigned to account %s;"
                + " cannot create external ID %s with the same email for account %s.",
            email,
            externalId.accountId().get(),
            extIdToBeCreated.key().get(),
            extIdToBeCreated.accountId().get());
        throw new AccountException("Email '" + email + "' in use by another account");
      }
    }
  }

  private void addGroupMember(AccountGroup.UUID groupUuid, IdentifiedUser user)
      throws IOException, ConfigInvalidException, AccountException {
    // The user initiated this request by logging in. -> Attribute all modifications to that user.
    GroupsUpdate groupsUpdate = groupsUpdateFactory.create(user);
    GroupDelta groupDelta =
        GroupDelta.builder()
            .setMemberModification(
                memberIds -> Sets.union(memberIds, ImmutableSet.of(user.getAccountId())))
            .build();
    try {
      groupsUpdate.updateGroup(groupUuid, groupDelta);
    } catch (NoSuchGroupException e) {
      throw new AccountException(String.format("Group %s not found", groupUuid), e);
    }
  }

  /**
   * Link another authentication identity to an existing account.
   *
   * @param to account to link the identity onto.
   * @param who the additional identity.
   * @return the result of linking the identity to the user.
   * @throws com.google.gerrit.server.account.AccountException the identity belongs to a different
   *     account, or it cannot be linked at this time.
   */
  @CanIgnoreReturnValue
  public AuthResult link(Account.Id to, AuthRequest who)
      throws AccountException, IOException, ConfigInvalidException {
    Optional<ExternalId> optionalExtId = externalIds.get(who.getExternalIdKey());
    if (optionalExtId.isPresent()) {
      ExternalId extId = optionalExtId.get();
      if (!extId.accountId().equals(to)) {
        throw new AccountException(
            "Identity '" + extId.key().get() + "' in use by another account");
      }
      update(who, extId);
    } else {
      ExternalId newExtId =
          externalIdFactory.createWithEmail(who.getExternalIdKey(), to, who.getEmailAddress());
      checkEmailNotUsed(to, newExtId);
      accountsUpdateProvider
          .get()
          .update(
              "Link External ID",
              to,
              (a, u) -> {
                u.addExternalId(newExtId);
                if (who.getEmailAddress() != null && a.account().preferredEmail() == null) {
                  u.setPreferredEmail(who.getEmailAddress());
                }
              });
    }
    return new AuthResult(to, who.getExternalIdKey(), false);
  }

  /**
   * Update the link to another unique authentication identity to an existing account.
   *
   * <p>Existing external identities with the same scheme will be removed and replaced with the new
   * one.
   *
   * @param to account to link the identity onto.
   * @param who the additional identity.
   * @return the result of linking the identity to the user.
   * @throws com.google.gerrit.server.account.AccountException the identity belongs to a different
   *     account, or it cannot be linked at this time.
   */
  @CanIgnoreReturnValue
  public AuthResult updateLink(Account.Id to, AuthRequest who)
      throws AccountException, IOException, ConfigInvalidException {
    Optional<ExternalId> optionalExtId = externalIds.get(who.getExternalIdKey());
    if (optionalExtId.filter(extId -> !extId.accountId().equals(to)).isPresent()) {
      throw new AccountException(
          "Identity '" + optionalExtId.get().key().get() + "' in use by another account");
    }

    accountsUpdateProvider
        .get()
        .updateForUserManagementRequests(
            "Update External IDs on Update Link",
            to,
            (a, u) -> {
              ImmutableSet<ExternalId> filteredExtIdsByScheme =
                  a.externalIds().stream()
                      .filter(e -> e.key().isScheme(who.getExternalIdKey().scheme()))
                      .collect(toImmutableSet());
              ExternalId newExtId =
                  externalIdFactory.createWithEmail(
                      who.getExternalIdKey(), to, who.getEmailAddress());

              u.replaceExternalIds(filteredExtIdsByScheme, Collections.singletonList(newExtId));
            });

    return new AuthResult(to, who.getExternalIdKey(), false);
  }

  /**
   * Link an external identity to an existing account.
   *
   * @param to account to link the external identity tp
   * @param extId external ID that should be added
   * @throws com.google.gerrit.server.account.AccountException the identity belongs to a different
   *     account, or the identity was not found
   */
  public void link(Account.Id to, ExternalId extId)
      throws AccountException, IOException, ConfigInvalidException {
    link(to, ImmutableList.of(extId));
  }

  /**
   * Link external identities to an existing account.
   *
   * @param to account to link the external identity to
   * @param extIds the external IDs that should be added. The IDs should already have been unlinked
   *     from the other account if they belong to another account. This method will create external
   *     IDs with the new accountID.
   * @throws com.google.gerrit.server.account.AccountException any of the identity belongs to a
   *     different account, or any of the identity was not found
   */
  public void link(Account.Id to, Collection<ExternalId> extIds)
      throws AccountException, IOException, ConfigInvalidException {
    if (extIds.isEmpty()) {
      return;
    }
    List<ExternalId> newExtIds = new ArrayList<>(extIds.size());
    ImmutableSet<ExternalId.Key> keys =
        extIds.stream().map(id -> id.key()).collect(toImmutableSet());

    Map<ExternalId.Key, ExternalId> existingExtIds =
        externalIds.get(keys).stream().collect(Collectors.toMap(ExternalId::key, id -> id));
    for (ExternalId extId : extIds) {
      if (!existingExtIds.containsKey(extId.key())) {
        // This is a brand new external ID so we link it directly
        newExtIds.add(extId);
        continue;
      }
      if (existingExtIds.get(extId.key()).accountId().equals(to)) {
        // This external already has the correct accountID set so we assume it's already linked
        continue;
      }
      // The externalID is linked to some other account so we throw an error here
      throw new AccountException("Identity '" + extId + "' in use by another account");
    }

    accountsUpdateProvider
        .get()
        .updateForUserManagementRequests(
            "Link External ID" + (newExtIds.size() > 1 ? "s" : ""),
            to,
            (a, u) -> {
              u.addExternalIds(newExtIds);
            });
  }

  /**
   * Unlink external identity from an existing account.
   *
   * @param from account to unlink the external identity from
   * @param extIdKey the key of the external ID that should be deleted
   * @throws com.google.gerrit.server.account.AccountException the identity belongs to a different
   *     account, or the identity was not found
   */
  public void unlink(Account.Id from, ExternalId.Key extIdKey)
      throws AccountException, IOException, ConfigInvalidException {
    unlink(from, ImmutableList.of(extIdKey));
  }

  /**
   * Unlink an external identities from an existing account.
   *
   * @param from account to unlink the external identity from
   * @param extIdKeys the keys of the external IDs that should be deleted
   * @throws com.google.gerrit.server.account.AccountException any of the identity belongs to a
   *     different account, or any of the identity was not found
   */
  public void unlink(Account.Id from, Collection<ExternalId.Key> extIdKeys)
      throws AccountException, IOException, ConfigInvalidException {
    if (extIdKeys.isEmpty()) {
      return;
    }

    List<ExternalId> extIds = new ArrayList<>(extIdKeys.size());
    for (ExternalId.Key extIdKey : extIdKeys) {
      Optional<ExternalId> extId = externalIds.get(extIdKey);
      if (extId.isPresent()) {
        if (!extId.get().accountId().equals(from)) {
          throw new AccountException("Identity '" + extIdKey.get() + "' in use by another account");
        }
        extIds.add(extId.get());
      } else {
        throw new AccountException("Identity '" + extIdKey.get() + "' not found");
      }
    }

    accountsUpdateProvider
        .get()
        .updateForUserManagementRequests(
            "Unlink External ID" + (extIds.size() > 1 ? "s" : ""),
            from,
            (a, u) -> {
              u.deleteExternalIds(extIds);
              if (a.account().preferredEmail() != null
                  && extIds.stream()
                      .anyMatch(e -> a.account().preferredEmail().equals(e.email()))) {
                u.setPreferredEmail(null);
              }
            });
  }
}
