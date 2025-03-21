// Copyright (C) 2019 The Android Open Source Project
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
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Comparator.comparing;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.gerrit.common.UsedAt;
import com.google.gerrit.common.UsedAt.Project;
import com.google.gerrit.entities.Account;
import com.google.gerrit.extensions.restapi.UnprocessableEntityException;
import com.google.gerrit.index.Schema;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.account.externalids.ExternalId;
import com.google.gerrit.server.config.AnonymousCowardName;
import com.google.gerrit.server.permissions.GlobalPermission;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.gerrit.server.query.account.InternalAccountQuery;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.eclipse.jgit.errors.ConfigInvalidException;

/**
 * Helper for resolving accounts given arbitrary user-provided input.
 *
 * <p>The {@code resolve*} methods each define a list of accepted formats for account resolution.
 * The algorithm for resolving accounts from a list of formats is as follows:
 *
 * <ol>
 *   <li>For each recognized format in the order listed in the method Javadoc, check whether the
 *       input matches that format.
 *   <li>If so, resolve accounts according to that format.
 *   <li>Filter out invisible and inactive accounts.
 *   <li>If the result list is non-empty, return.
 *   <li>If the format is listed above as being short-circuiting, return.
 *   <li>Otherwise, return to step 1 with the next format.
 * </ol>
 *
 * <p>The result never includes accounts that are not visible to the calling user. It also never
 * includes inactive accounts, with a small number of specific exceptions noted in method Javadoc.
 */
@Singleton
public class AccountResolver {
  public static class UnresolvableAccountException extends UnprocessableEntityException {
    private static final long serialVersionUID = 1L;
    private final Result result;

    @VisibleForTesting
    UnresolvableAccountException(Result result) {
      super(exceptionMessage(result));
      this.result = result;
    }

    public boolean isSelf() {
      return result.isSelf();
    }
  }

  public static String exceptionMessage(Result result) {
    checkArgument(result.asList().size() != 1);
    if (result.asList().isEmpty()) {
      if (result.isSelf()) {
        return "Resolving account '" + result.input() + "' requires login";
      }
      if (result.filteredInactive().isEmpty()) {
        return "Account '" + result.input() + "' not found";
      }
      return result.filteredInactive().stream()
          .map(a -> formatForException(result, a))
          .collect(
              joining(
                  "\n",
                  "Account '"
                      + result.input()
                      + "' only matches inactive accounts. To use an inactive account, retry with"
                      + " one of the following exact account IDs:\n",
                  ""));
    }

    return result.asList().stream()
        .map(a -> formatForException(result, a))
        .limit(3)
        .collect(
            joining(
                "\n", "Account '" + result.input() + "' is ambiguous (at most 3 shown):\n", ""));
  }

  private static String formatForException(Result result, AccountState state) {
    return state.account().id()
        + ": "
        + state.account().getNameEmail(result.accountResolver().anonymousCowardName);
  }

  public static boolean isSelf(String input) {
    return "self".equals(input) || "me".equals(input);
  }

  public class Result {
    private final String input;
    private final ImmutableList<AccountState> list;
    private final ImmutableList<AccountState> filteredInactive;
    private final CurrentUser searchedAsUser;

    @VisibleForTesting
    Result(
        String input,
        List<AccountState> list,
        List<AccountState> filteredInactive,
        CurrentUser searchedAsUser) {
      this.input = requireNonNull(input);
      this.list = canonicalize(list);
      this.filteredInactive = canonicalize(filteredInactive);
      this.searchedAsUser = requireNonNull(searchedAsUser);
    }

    private ImmutableList<AccountState> canonicalize(List<AccountState> list) {
      TreeSet<AccountState> set = new TreeSet<>(comparing(a -> a.account().id().get()));
      set.addAll(requireNonNull(list));
      return ImmutableList.copyOf(set);
    }

    public String input() {
      return input;
    }

    public boolean isSelf() {
      return AccountResolver.isSelf(input);
    }

    public ImmutableList<AccountState> asList() {
      return list;
    }

    public ImmutableSet<Account.Id> asNonEmptyIdSet() throws UnresolvableAccountException {
      if (list.isEmpty()) {
        throw new UnresolvableAccountException(this);
      }
      return asIdSet();
    }

    public ImmutableSet<Account.Id> asIdSet() {
      return list.stream().map(a -> a.account().id()).collect(toImmutableSet());
    }

    public AccountState asUnique() throws UnresolvableAccountException {
      ensureUnique();
      return list.get(0);
    }

    private void ensureUnique() throws UnresolvableAccountException {
      if (list.size() != 1) {
        throw new UnresolvableAccountException(this);
      }
    }

    private void ensureSelfIsUniqueIdentifiedUser() throws UnresolvableAccountException {
      ensureUnique();
      if (!searchedAsUser.isIdentifiedUser()) {
        throw new UnresolvableAccountException(this);
      }
    }

    public IdentifiedUser asUniqueUser() throws UnresolvableAccountException {
      if (isSelf()) {
        ensureSelfIsUniqueIdentifiedUser();
        // In the special case of "self", use the exact IdentifiedUser from the request context, to
        // preserve the peer address and any other per-request state.
        return searchedAsUser.asIdentifiedUser();
      }
      ensureUnique();
      return userFactory.create(asUnique());
    }

    public IdentifiedUser asUniqueUserOnBehalfOf(CurrentUser caller)
        throws UnresolvableAccountException {
      ensureUnique();
      if (isSelf()) {
        return searchedAsUser.asIdentifiedUser();
      }
      return userFactory.runAs(
          /* remotePeer= */ null, list.get(0).account().id(), requireNonNull(caller).getRealUser());
    }

    @VisibleForTesting
    ImmutableList<AccountState> filteredInactive() {
      return filteredInactive;
    }

    private AccountResolver accountResolver() {
      return AccountResolver.this;
    }
  }

  @VisibleForTesting
  interface Searcher<I> {
    default boolean callerShouldFilterOutInactiveCandidates() {
      return true;
    }

    /**
     * Searches can be done on behalf of either the current user or another provided user. The
     * results of some searchers, such as BySelf, are affected by the context user.
     */
    default boolean requiresContextUser() {
      return false;
    }

    Optional<I> tryParse(String input) throws IOException;

    /**
     * This method should be implemented for every searcher which doesn't require a context user.
     *
     * @param input to search for
     * @return stream of the matching accounts
     * @throws IOException by some subclasses
     * @throws ConfigInvalidException by some subclasses
     */
    default Stream<AccountState> search(I input) throws IOException, ConfigInvalidException {
      throw new IllegalStateException("search(I) default implementation should never be called.");
    }

    /**
     * This method should be implemented for every searcher which requires a context user.
     *
     * @param input to search for
     * @param asUser the context user for the search
     * @return stream of the matching accounts
     * @throws IOException by some subclasses
     * @throws ConfigInvalidException by some subclasses
     */
    default Stream<AccountState> search(I input, CurrentUser asUser)
        throws IOException, ConfigInvalidException {
      if (!requiresContextUser()) {
        return search(input);
      }
      throw new IllegalStateException(
          "The searcher requires a context user, but doesn't implement search(input, asUser).");
    }

    boolean shortCircuitIfNoResults();

    default Optional<Stream<AccountState>> trySearch(String input, CurrentUser asUser)
        throws IOException, ConfigInvalidException {
      Optional<I> parsed = tryParse(input);
      if (parsed.isEmpty()) {
        return Optional.empty();
      }
      return requiresContextUser()
          ? Optional.of(search(parsed.get(), asUser))
          : Optional.of(search(parsed.get()));
    }
  }

  @VisibleForTesting
  abstract static class StringSearcher implements Searcher<String> {
    @Override
    public final Optional<String> tryParse(String input) {
      return matches(input) ? Optional.of(input) : Optional.empty();
    }

    protected abstract boolean matches(String input);
  }

  private abstract class AccountIdSearcher implements Searcher<Account.Id> {
    @Override
    public final Stream<AccountState> search(Account.Id input) {
      return accountCache.get(input).stream();
    }
  }

  private static class BySelf extends StringSearcher {
    @Override
    public boolean callerShouldFilterOutInactiveCandidates() {
      return false;
    }

    @Override
    public boolean requiresContextUser() {
      return true;
    }

    @Override
    protected boolean matches(String input) {
      return "self".equals(input) || "me".equals(input);
    }

    @Override
    public Stream<AccountState> search(String input, CurrentUser asUser) {
      if (!asUser.isIdentifiedUser()) {
        return Stream.empty();
      }
      return Stream.of(asUser.asIdentifiedUser().state());
    }

    @Override
    public boolean shortCircuitIfNoResults() {
      return true;
    }
  }

  private class ByExactAccountId extends AccountIdSearcher {
    @Override
    public boolean callerShouldFilterOutInactiveCandidates() {
      return false;
    }

    @Override
    public Optional<Account.Id> tryParse(String input) {
      return Account.Id.tryParse(input);
    }

    @Override
    public boolean shortCircuitIfNoResults() {
      return true;
    }
  }

  private class ByParenthesizedAccountId extends AccountIdSearcher {
    private final Pattern pattern = Pattern.compile("^.* \\(([1-9][0-9]*)\\)$");

    @Override
    public Optional<Account.Id> tryParse(String input) {
      Matcher m = pattern.matcher(input);
      return m.matches() ? Account.Id.tryParse(m.group(1)) : Optional.empty();
    }

    @Override
    public boolean shortCircuitIfNoResults() {
      return true;
    }
  }

  private class ByUsername extends StringSearcher {
    @Override
    public boolean matches(String input) {
      return ExternalId.isValidUsername(input);
    }

    @Override
    public Stream<AccountState> search(String input) {
      return accountCache.getByUsername(input).stream();
    }

    @Override
    public boolean shortCircuitIfNoResults() {
      return false;
    }
  }

  private class ByNameAndEmail extends StringSearcher {
    @Override
    protected boolean matches(String input) {
      int lt = input.indexOf('<');
      int gt = input.indexOf('>');
      return lt >= 0 && gt > lt && input.contains("@");
    }

    @Override
    public Stream<AccountState> search(String nameOrEmail) throws IOException {
      // TODO(dborowitz): This would probably work as a Searcher<Address>
      int lt = nameOrEmail.indexOf('<');
      int gt = nameOrEmail.indexOf('>');
      ImmutableSet<Account.Id> ids = emails.getAccountFor(nameOrEmail.substring(lt + 1, gt));
      ImmutableList<AccountState> allMatches = toAccountStates(ids).collect(toImmutableList());
      if (allMatches.isEmpty() || allMatches.size() == 1) {
        return allMatches.stream();
      }

      // More than one match. If there are any that match the full name as well, return only that
      // subset. Otherwise, all are equally non-matching, so return the full set.
      if (lt == 0) {
        // No name was specified in the input string.
        return allMatches.stream();
      }
      String name = nameOrEmail.substring(0, lt - 1);
      ImmutableList<AccountState> nameMatches =
          allMatches.stream()
              .filter(a -> name.equals(a.account().fullName()))
              .collect(toImmutableList());
      return !nameMatches.isEmpty() ? nameMatches.stream() : allMatches.stream();
    }

    @Override
    public boolean shortCircuitIfNoResults() {
      return true;
    }
  }

  private class ByEmail extends StringSearcher {
    @Override
    public boolean requiresContextUser() {
      return true;
    }

    @Override
    protected boolean matches(String input) {
      return input.contains("@");
    }

    @Override
    public Stream<AccountState> search(String input, CurrentUser asUser) throws IOException {
      boolean canViewSecondaryEmails = false;
      try {
        if (permissionBackend.user(asUser).test(GlobalPermission.VIEW_SECONDARY_EMAILS)) {
          canViewSecondaryEmails = true;
        }
      } catch (PermissionBackendException e) {
        // remains false
      }

      if (canViewSecondaryEmails) {
        return toAccountStates(emails.getAccountFor(input));
      }

      // User cannot see secondary emails, hence search by preferred email only.
      List<AccountState> accountStates = accountQueryProvider.get().byPreferredEmail(input);

      if (accountStates.size() == 1) {
        return Stream.of(Iterables.getOnlyElement(accountStates));
      }

      if (accountStates.size() > 1) {
        // An email can only belong to a single account. If multiple accounts are found it means
        // there is an inconsistency, i.e. some of the found accounts have a preferred email set
        // that they do not own via an external ID. Hence in this case we return only the one
        // account that actually owns the email via an external ID.
        for (AccountState accountState : accountStates) {
          if (accountState.externalIds().stream()
              .map(ExternalId::email)
              .filter(Objects::nonNull)
              .anyMatch(email -> email.equals(input))) {
            return Stream.of(accountState);
          }
        }

        // None of the matched accounts owns the email, return all matches to be consistent with
        // the behavior of Emails.getAccountFor(String) that is used above if the user can see
        // secondary emails.
        return accountStates.stream();
      }

      // No match by preferred email. Since users can always see their own secondary emails, check
      // if the input matches a secondary email of the user and if yes, return the account of the
      // user.
      if (asUser.isIdentifiedUser()
          && asUser.asIdentifiedUser().state().externalIds().stream()
              .map(ExternalId::email)
              .filter(Objects::nonNull)
              .anyMatch(email -> email.equals(input))) {
        return Stream.of(asUser.asIdentifiedUser().state());
      }

      // No match.
      return Stream.empty();
    }

    @Override
    public boolean shortCircuitIfNoResults() {
      return true;
    }
  }

  private class FromRealm extends AccountIdSearcher {
    @Override
    public Optional<Account.Id> tryParse(String input) throws IOException {
      return Optional.ofNullable(realm.lookup(input));
    }

    @Override
    public boolean shortCircuitIfNoResults() {
      return false;
    }
  }

  private class ByFullName extends StringSearcher {
    ByFullName() {
      super();
    }

    @Override
    protected boolean matches(String input) {
      return true;
    }

    @Override
    public Stream<AccountState> search(String input) {
      return accountQueryProvider.get().byFullName(input).stream();
    }

    @Override
    public boolean shortCircuitIfNoResults() {
      return false;
    }
  }

  private class ByDefaultSearch extends StringSearcher {
    ByDefaultSearch() {
      super();
    }

    @Override
    public boolean requiresContextUser() {
      return true;
    }

    @Override
    protected boolean matches(String input) {
      return true;
    }

    @Override
    public Stream<AccountState> search(String input, CurrentUser asUser) {
      // At this point we have no clue. Just perform a whole bunch of suggestions and pray we come
      // up with a reasonable result list.
      // TODO(dborowitz): This doesn't match the documentation; consider whether it's possible to be
      // more strict here.
      boolean canViewSecondaryEmails = false;
      try {
        if (permissionBackend.user(asUser).test(GlobalPermission.VIEW_SECONDARY_EMAILS)) {
          canViewSecondaryEmails = true;
        }
      } catch (PermissionBackendException e) {
        // remains false
      }
      return accountQueryProvider.get().byDefault(input, canViewSecondaryEmails).stream();
    }

    @Override
    public boolean shortCircuitIfNoResults() {
      // In practice this doesn't matter since this is the last searcher in the list, but considered
      // on its own, it doesn't necessarily need to be terminal.
      return false;
    }
  }

  private final ImmutableList<Searcher<?>> nameOrEmailSearchers =
      ImmutableList.of(
          new ByNameAndEmail(),
          new ByEmail(),
          new FromRealm(),
          new ByFullName(),
          new ByDefaultSearch());

  private final ImmutableList<Searcher<?>> searchers =
      ImmutableList.<Searcher<?>>builder()
          .add(new BySelf())
          .add(new ByExactAccountId())
          .add(new ByParenthesizedAccountId())
          .add(new ByUsername())
          .addAll(nameOrEmailSearchers)
          .build();

  private final ImmutableList<Searcher<?>> exactSearchers =
      ImmutableList.<Searcher<?>>builder()
          .add(new BySelf())
          .add(new ByExactAccountId())
          .add(new ByEmail())
          .add(new ByUsername())
          .build();

  private final AccountCache accountCache;
  private final AccountControl.Factory accountControlFactory;
  private final Emails emails;
  private final IdentifiedUser.GenericFactory userFactory;
  private final Provider<CurrentUser> self;
  private final Provider<InternalAccountQuery> accountQueryProvider;
  private final Realm realm;
  private final String anonymousCowardName;
  private final PermissionBackend permissionBackend;

  @Inject
  AccountResolver(
      AccountCache accountCache,
      Emails emails,
      AccountControl.Factory accountControlFactory,
      IdentifiedUser.GenericFactory userFactory,
      Provider<CurrentUser> self,
      Provider<InternalAccountQuery> accountQueryProvider,
      PermissionBackend permissionBackend,
      Realm realm,
      @AnonymousCowardName String anonymousCowardName) {
    this.accountCache = accountCache;
    this.emails = emails;
    this.accountControlFactory = accountControlFactory;
    this.userFactory = userFactory;
    this.self = self;
    this.accountQueryProvider = accountQueryProvider;
    this.permissionBackend = permissionBackend;
    this.realm = realm;
    this.anonymousCowardName = anonymousCowardName;
  }

  /**
   * Resolves all accounts matching the input string, visible to the current user.
   *
   * <p>The following input formats are recognized:
   *
   * <ul>
   *   <li>The strings {@code "self"} and {@code "me"}, if the current user is an {@link
   *       IdentifiedUser}. In this case, may return exactly one inactive account.
   *   <li>A bare account ID ({@code "18419"}). In this case, may return exactly one inactive
   *       account. This case short-circuits if the input matches.
   *   <li>An account ID in parentheses following a full name ({@code "Full Name (18419)"}). This
   *       case short-circuits if the input matches.
   *   <li>A username ({@code "username"}).
   *   <li>A full name and email address ({@code "Full Name <email@example>"}). This case
   *       short-circuits if the input matches.
   *   <li>An email address ({@code "email@example"}. This case short-circuits if the input matches.
   *   <li>An account name recognized by the configured {@link Realm#lookup(String)} Realm}.
   *   <li>A full name ({@code "Full Name"}).
   *   <li>As a fallback, a {@link
   *       com.google.gerrit.server.query.account.AccountPredicates#defaultPredicate(Schema,
   *       boolean, String) default search} against the account index.
   * </ul>
   *
   * @param input input string.
   * @return a result describing matching accounts. Never null even if the result set is empty.
   * @throws ConfigInvalidException if an error occurs.
   * @throws IOException if an error occurs.
   */
  public Result resolve(String input) throws ConfigInvalidException, IOException {
    return searchImpl(
        input, searchers, self.get(), this::currentUserCanSeePredicate, AccountResolver::isActive);
  }

  /** Resolves accounts using exact searchers. Similar to the previous method. */
  @UsedAt(Project.GOOGLE)
  public Result resolveExact(String input) throws ConfigInvalidException, IOException {
    return searchImpl(
        input,
        exactSearchers,
        self.get(),
        this::currentUserCanSeePredicate,
        AccountResolver::isActive);
  }

  public Result resolve(String input, Predicate<AccountState> accountActivityPredicate)
      throws ConfigInvalidException, IOException {
    return searchImpl(
        input, searchers, self.get(), this::currentUserCanSeePredicate, accountActivityPredicate);
  }

  /**
   * Resolves all accounts matching the input string, visible to the provided user.
   *
   * <p>The following input formats are recognized:
   *
   * <ul>
   *   <li>The strings {@code "self"} and {@code "me"}, if the provided user is an {@link
   *       IdentifiedUser}. In this case, may return exactly one inactive account.
   *   <li>A bare account ID ({@code "18419"}). In this case, may return exactly one inactive
   *       account. This case short-circuits if the input matches.
   *   <li>An account ID in parentheses following a full name ({@code "Full Name (18419)"}). This
   *       case short-circuits if the input matches.
   *   <li>A username ({@code "username"}).
   *   <li>A full name and email address ({@code "Full Name <email@example>"}). This case
   *       short-circuits if the input matches.
   *   <li>An email address ({@code "email@example"}. This case short-circuits if the input matches.
   *   <li>An account name recognized by the configured {@link Realm#lookup(String)} Realm}.
   *   <li>A full name ({@code "Full Name"}).
   *   <li>As a fallback, a {@link
   *       com.google.gerrit.server.query.account.AccountPredicates#defaultPredicate(Schema,
   *       boolean, String) default search} against the account index.
   * </ul>
   *
   * @param asUser user to resolve the users by.
   * @param input input string.
   * @return a result describing matching accounts. Never null even if the result set is empty.
   * @throws ConfigInvalidException if an error occurs.
   * @throws IOException if an error occurs.
   */
  public Result resolveAsUser(CurrentUser asUser, String input)
      throws ConfigInvalidException, IOException {
    return resolveAsUser(asUser, input, AccountResolver::isActive);
  }

  public Result resolveAsUser(
      CurrentUser asUser, String input, Predicate<AccountState> accountActivityPredicate)
      throws ConfigInvalidException, IOException {
    return searchImpl(
        input,
        searchers,
        asUser,
        new ProvidedUserCanSeePredicate(asUser),
        accountActivityPredicate);
  }

  /**
   * As opposed to {@link #resolve}, the returned result includes all inactive accounts for the
   * input search.
   *
   * <p>This can be used to resolve Gerrit Account from email to its {@link
   * com.google.gerrit.entities.Account.Id}, to make sure that if {@link Account} with such email
   * exists in Gerrit (even inactive), user data (email address) won't be recorded as it is, but
   * instead will be stored as a link to the corresponding Gerrit Account.
   */
  public Result resolveIncludeInactive(String input) throws ConfigInvalidException, IOException {
    return searchImpl(
        input,
        searchers,
        self.get(),
        this::currentUserCanSeePredicate,
        AccountResolver::allVisible);
  }

  public Result resolveIncludeInactiveIgnoreVisibility(String input)
      throws ConfigInvalidException, IOException {
    return searchImpl(
        input, searchers, self.get(), this::allVisiblePredicate, AccountResolver::allVisible);
  }

  public Result resolveIgnoreVisibility(String input) throws ConfigInvalidException, IOException {
    return searchImpl(
        input, searchers, self.get(), this::allVisiblePredicate, AccountResolver::isActive);
  }

  @UsedAt(UsedAt.Project.PLUGIN_REVIEWERS)
  public Result resolveExactIgnoreVisibility(String input)
      throws ConfigInvalidException, IOException {
    return searchImpl(
        input, exactSearchers, self.get(), this::allVisiblePredicate, AccountResolver::isActive);
  }

  public Result resolveAsUserIgnoreVisibility(CurrentUser asUser, String input)
      throws ConfigInvalidException, IOException {
    return resolveAsUserIgnoreVisibility(asUser, input, AccountResolver::isActive);
  }

  public Result resolveAsUserIgnoreVisibility(
      CurrentUser asUser, String input, Predicate<AccountState> accountActivityPredicate)
      throws ConfigInvalidException, IOException {
    return searchImpl(
        input, searchers, asUser, this::allVisiblePredicate, accountActivityPredicate);
  }

  /**
   * Resolves all accounts matching the input string by name or email.
   *
   * <p>The following input formats are recognized:
   *
   * <ul>
   *   <li>A full name and email address ({@code "Full Name <email@example>"}). This case
   *       short-circuits if the input matches.
   *   <li>An email address ({@code "email@example"}. This case short-circuits if the input matches.
   *   <li>An account name recognized by the configured {@link Realm#lookup(String)} Realm}.
   *   <li>A full name ({@code "Full Name"}).
   *   <li>As a fallback, a {@link
   *       com.google.gerrit.server.query.account.AccountPredicates#defaultPredicate(Schema,
   *       boolean, String) default search} against the account index.
   * </ul>
   *
   * @param input input string.
   * @return a result describing matching accounts. Never null even if the result set is empty.
   * @throws ConfigInvalidException if an error occurs.
   * @throws IOException if an error occurs.
   * @deprecated for use only by MailUtil for parsing commit footers; that class needs to be
   *     reevaluated.
   */
  @Deprecated
  public Result resolveByNameOrEmail(String input) throws ConfigInvalidException, IOException {
    return searchImpl(
        input,
        nameOrEmailSearchers,
        self.get(),
        this::currentUserCanSeePredicate,
        AccountResolver::isActive);
  }

  /**
   * Same as {@link #resolveByNameOrEmail(String)}, but with exact matching for the full name, email
   * and full name.
   *
   * @param input input string.
   * @return a result describing matching accounts. Never null even if the result set is empty.
   * @throws ConfigInvalidException if an error occurs.
   * @throws IOException if an error occurs.
   * @deprecated for use only by MailUtil for parsing commit footers; that class needs to be
   *     reevaluated.
   */
  @Deprecated
  public Result resolveByExactNameOrEmail(String input) throws ConfigInvalidException, IOException {
    return searchImpl(
        input,
        ImmutableList.of(new ByNameAndEmail(), new ByEmail(), new ByFullName(), new ByUsername()),
        self.get(),
        this::currentUserCanSeePredicate,
        AccountResolver::isActive);
  }

  private Predicate<AccountState> currentUserCanSeePredicate() {
    return accountControlFactory.get()::canSee;
  }

  private class ProvidedUserCanSeePredicate implements Supplier<Predicate<AccountState>> {
    CurrentUser asUser;

    ProvidedUserCanSeePredicate(CurrentUser asUser) {
      this.asUser = asUser;
    }

    @Override
    public Predicate<AccountState> get() {
      return accountControlFactory.get(asUser)::canSee;
    }
  }

  private Predicate<AccountState> allVisiblePredicate() {
    return AccountResolver::allVisible;
  }

  /**
   * @param accountState account state for which the visibility should be checked
   */
  private static boolean allVisible(AccountState accountState) {
    return true;
  }

  private static boolean isActive(AccountState accountState) {
    return accountState.account().isActive();
  }

  @VisibleForTesting
  Result searchImpl(
      String input,
      ImmutableList<Searcher<?>> searchers,
      CurrentUser asUser,
      Supplier<Predicate<AccountState>> visibilitySupplier,
      Predicate<AccountState> accountActivityPredicate)
      throws ConfigInvalidException, IOException {
    requireNonNull(asUser);
    visibilitySupplier = Suppliers.memoize(visibilitySupplier::get);
    List<AccountState> inactive = new ArrayList<>();

    for (Searcher<?> searcher : searchers) {
      Optional<Stream<AccountState>> maybeResults = searcher.trySearch(input, asUser);
      if (!maybeResults.isPresent()) {
        continue;
      }
      Stream<AccountState> results = maybeResults.get();

      // Filter out non-visible results, except if it's the BySelf searcher. Since users can always
      // see themselves checking the visibility is not needed for the BySelf searcher.
      results = searcher instanceof BySelf ? results : results.filter(visibilitySupplier.get());

      List<AccountState> list;
      if (searcher.callerShouldFilterOutInactiveCandidates()) {
        // Keep track of all inactive candidates discovered by any searchers. If we end up short-
        // circuiting, the inactive list will be discarded.
        List<AccountState> active = new ArrayList<>();
        results.forEach(a -> (accountActivityPredicate.test(a) ? active : inactive).add(a));
        list = active;
      } else {
        list = results.collect(toImmutableList());
      }

      if (!list.isEmpty()) {
        return createResult(input, list, asUser);
      }
      if (searcher.shortCircuitIfNoResults()) {
        // For a short-circuiting searcher, return results even if empty.
        return !inactive.isEmpty()
            ? emptyResult(input, inactive, asUser)
            : createResult(input, list, asUser);
      }
    }
    return emptyResult(input, inactive, asUser);
  }

  private Result createResult(String input, List<AccountState> list, CurrentUser searchedAsUser) {
    return new Result(input, list, ImmutableList.of(), searchedAsUser);
  }

  private Result emptyResult(
      String input, List<AccountState> inactive, CurrentUser searchedAsUser) {
    return new Result(input, ImmutableList.of(), inactive, searchedAsUser);
  }

  private Stream<AccountState> toAccountStates(Set<Account.Id> ids) {
    return accountCache.get(ids).values().stream();
  }
}
