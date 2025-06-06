// Copyright (C) 2013 The Android Open Source Project
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

import static com.google.common.collect.Streams.stream;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;
import static java.util.stream.Stream.concat;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;
import com.google.gerrit.entities.Account;
import com.google.gerrit.extensions.common.AccountInfo;
import com.google.gerrit.extensions.common.AccountInfo.Tags;
import com.google.gerrit.extensions.common.AvatarInfo;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.extensions.registration.DynamicMap;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.account.externalids.ExternalId;
import com.google.gerrit.server.avatar.AvatarProvider;
import com.google.gerrit.server.data.AccountAttribute;
import com.google.gerrit.server.permissions.GlobalPermission;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

@Singleton
public class InternalAccountDirectory extends AccountDirectory {
  static final Set<FillOptions> ID_ONLY = Collections.unmodifiableSet(EnumSet.of(FillOptions.ID));
  static final Set<FillOptions> ALL_ACCOUNT_ATTRIBUTES =
      Collections.unmodifiableSet(
          EnumSet.of(FillOptions.NAME, FillOptions.EMAIL, FillOptions.USERNAME));

  public static class InternalAccountDirectoryModule extends AbstractModule {
    @Override
    protected void configure() {
      bind(AccountDirectory.class).to(InternalAccountDirectory.class);
    }
  }

  private final AccountCache accountCache;
  private final DynamicItem<AvatarProvider> avatar;
  private final IdentifiedUser.GenericFactory userFactory;
  private final Provider<CurrentUser> self;
  private final PermissionBackend permissionBackend;
  private final ServiceUserClassifier serviceUserClassifier;
  private final DynamicMap<AccountTagProvider> accountTagProviders;

  @Inject
  InternalAccountDirectory(
      AccountCache accountCache,
      DynamicItem<AvatarProvider> avatar,
      IdentifiedUser.GenericFactory userFactory,
      Provider<CurrentUser> self,
      PermissionBackend permissionBackend,
      ServiceUserClassifier serviceUserClassifier,
      DynamicMap<AccountTagProvider> accountTagProviders) {
    this.accountCache = accountCache;
    this.avatar = avatar;
    this.userFactory = userFactory;
    this.self = self;
    this.permissionBackend = permissionBackend;
    this.serviceUserClassifier = serviceUserClassifier;
    this.accountTagProviders = accountTagProviders;
  }

  @Override
  public void fillAccountInfo(Iterable<? extends AccountInfo> in, Set<FillOptions> options)
      throws PermissionBackendException {
    if (options.equals(ID_ONLY)) {
      return;
    }

    boolean canViewSecondaryEmails = false;
    Account.Id currentUserId = null;
    if (self.get().isIdentifiedUser()) {
      currentUserId = self.get().getAccountId();
      if (permissionBackend.currentUser().test(GlobalPermission.VIEW_SECONDARY_EMAILS)) {
        canViewSecondaryEmails = true;
      }
    }

    Set<FillOptions> fillOptionsWithoutSecondaryEmails =
        Sets.difference(options, EnumSet.of(FillOptions.SECONDARY_EMAILS));
    Set<Account.Id> ids = stream(in).map(a -> Account.id(a._accountId)).collect(toSet());
    ImmutableMap<Account.Id, AccountState> accountStates = accountCache.get(ids);
    for (AccountInfo info : in) {
      Account.Id id = Account.id(info._accountId);
      AccountState state = accountStates.get(id);
      if (state != null) {
        if (!options.contains(FillOptions.SECONDARY_EMAILS)
            || Objects.equals(currentUserId, state.account().id())
            || canViewSecondaryEmails) {
          fill(info, accountStates.get(id), options);
        } else {
          // user is not allowed to see secondary emails
          fill(info, accountStates.get(id), fillOptionsWithoutSecondaryEmails);
        }

      } else {
        info._accountId = options.contains(FillOptions.ID) ? id.get() : null;
      }
    }
  }

  @Override
  public void fillAccountAttributeInfo(Iterable<? extends AccountAttribute> in) {
    Set<Account.Id> ids = stream(in).map(a -> Account.id(a.accountId)).collect(toSet());
    ImmutableMap<Account.Id, AccountState> accountStates = accountCache.get(ids);
    for (AccountAttribute accountAttribute : in) {
      Account.Id id = Account.id(accountAttribute.accountId);
      AccountState accountState = accountStates.get(id);
      if (accountState != null) {
        fill(accountAttribute, accountState, ALL_ACCOUNT_ATTRIBUTES);
      } else {
        accountAttribute.accountId = null;
      }
    }
  }

  private void fill(
      AccountAttribute accountAttribute, AccountState accountState, Set<FillOptions> options) {
    Account account = accountState.account();
    if (options.contains(FillOptions.NAME)) {
      accountAttribute.name = Strings.emptyToNull(account.fullName());
      if (accountAttribute.name == null) {
        accountAttribute.name = accountState.userName().orElse(null);
      }
    }
    if (options.contains(FillOptions.EMAIL)) {
      accountAttribute.email = account.preferredEmail();
    }
    if (options.contains(FillOptions.USERNAME)) {
      accountAttribute.username = accountState.userName().orElse(null);
    }
    if (options.contains(FillOptions.ID)) {
      accountAttribute.accountId = account.id().get();
    } else {
      // Was previously set to look up account for filling.
      accountAttribute.accountId = null;
    }
  }

  private void fill(AccountInfo info, AccountState accountState, Set<FillOptions> options) {
    Account account = accountState.account();
    if (options.contains(FillOptions.ID)) {
      info._accountId = account.id().get();
    } else {
      // Was previously set to look up account for filling.
      info._accountId = null;
    }
    if (options.contains(FillOptions.NAME)) {
      info.name = Strings.emptyToNull(account.fullName());
      if (info.name == null) {
        info.name = accountState.userName().orElse(null);
      }
    }
    if (options.contains(FillOptions.EMAIL)) {
      info.email = account.preferredEmail();
    }
    if (options.contains(FillOptions.SECONDARY_EMAILS)) {
      info.secondaryEmails = getSecondaryEmails(account, accountState.externalIds());
    }
    if (options.contains(FillOptions.USERNAME)) {
      info.username = accountState.userName().orElse(null);
    }

    if (options.contains(FillOptions.DISPLAY_NAME)) {
      info.displayName = account.displayName();
    }

    if (options.contains(FillOptions.STATUS)) {
      info.status = account.status();
    }

    if (options.contains(FillOptions.STATE)) {
      info.inactive = account.inactive() ? true : null;
    }

    if (options.contains(FillOptions.TAGS)) {
      List<String> tags = getTags(account.id());
      if (!tags.isEmpty()) {
        info.tags = tags;
      }
    }

    if (options.contains(FillOptions.AVATARS)) {
      AvatarProvider ap = avatar.get();
      if (ap != null) {
        info.avatars = new ArrayList<>();
        IdentifiedUser user = userFactory.create(accountState);

        // PolyGerrit UI uses the following sizes for avatars:
        // - 32px for avatars next to names e.g. on the dashboard. This is also Gerrit's default.
        // - 56px for the user's own avatar in the menu
        // - 100ox for other user's avatars on dashboards
        // - 120px for the user's own profile settings page
        addAvatar(ap, info, user, AvatarInfo.DEFAULT_SIZE);
        if (!info.avatars.isEmpty()) {
          addAvatar(ap, info, user, 56);
          addAvatar(ap, info, user, 100);
          addAvatar(ap, info, user, 120);
        }
      }
    }
  }

  public List<String> getSecondaryEmails(Account account, Collection<ExternalId> externalIds) {
    return ExternalId.getEmails(externalIds)
        .filter(e -> !e.equals(account.preferredEmail()))
        .sorted()
        .collect(toList());
  }

  private List<String> getTags(Account.Id id) {
    Stream<String> tagsFromProviders =
        stream(accountTagProviders.iterator())
            .flatMap(accountTagProvider -> accountTagProvider.get().getTags(id).stream());
    Stream<String> tagsFromServiceUserClassifier =
        serviceUserClassifier.isServiceUser(id) ? Stream.of(Tags.SERVICE_USER) : Stream.empty();
    return concat(tagsFromProviders, tagsFromServiceUserClassifier).collect(toList());
  }

  private static void addAvatar(
      AvatarProvider provider, AccountInfo account, IdentifiedUser user, int size) {
    String url = provider.getUrl(user, size);
    if (url != null) {
      AvatarInfo avatar = new AvatarInfo();
      avatar.url = url;
      avatar.height = size;
      account.avatars.add(avatar);
    }
  }
}
