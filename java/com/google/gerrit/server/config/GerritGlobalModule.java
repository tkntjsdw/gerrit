// Copyright (C) 2016 The Android Open Source Project
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

package com.google.gerrit.server.config;

import static com.google.inject.Scopes.SINGLETON;

import com.google.common.base.Ticker;
import com.google.common.cache.Cache;
import com.google.gerrit.entities.SubmitRequirement;
import com.google.gerrit.extensions.annotations.Exports;
import com.google.gerrit.extensions.api.changes.ActionVisitor;
import com.google.gerrit.extensions.api.projects.CommentLinkInfo;
import com.google.gerrit.extensions.auth.oauth.OAuthLoginProvider;
import com.google.gerrit.extensions.auth.oauth.OAuthTokenEncrypter;
import com.google.gerrit.extensions.common.AccountDefaultDisplayName;
import com.google.gerrit.extensions.common.AccountVisibility;
import com.google.gerrit.extensions.config.CapabilityDefinition;
import com.google.gerrit.extensions.config.CloneCommand;
import com.google.gerrit.extensions.config.DownloadCommand;
import com.google.gerrit.extensions.config.DownloadScheme;
import com.google.gerrit.extensions.config.ExternalIncludedIn;
import com.google.gerrit.extensions.config.FactoryModule;
import com.google.gerrit.extensions.config.PluginProjectPermissionDefinition;
import com.google.gerrit.extensions.events.AccountActivationListener;
import com.google.gerrit.extensions.events.AccountIndexedListener;
import com.google.gerrit.extensions.events.AgreementSignupListener;
import com.google.gerrit.extensions.events.AttentionSetListener;
import com.google.gerrit.extensions.events.ChangeAbandonedListener;
import com.google.gerrit.extensions.events.ChangeDeletedListener;
import com.google.gerrit.extensions.events.ChangeIndexedListener;
import com.google.gerrit.extensions.events.ChangeMergedListener;
import com.google.gerrit.extensions.events.ChangeRestoredListener;
import com.google.gerrit.extensions.events.ChangeRevertedListener;
import com.google.gerrit.extensions.events.CommentAddedListener;
import com.google.gerrit.extensions.events.CustomKeyedValuesEditedListener;
import com.google.gerrit.extensions.events.GarbageCollectorListener;
import com.google.gerrit.extensions.events.GitBatchRefUpdateListener;
import com.google.gerrit.extensions.events.GitReferenceUpdatedListener;
import com.google.gerrit.extensions.events.GroupIndexedListener;
import com.google.gerrit.extensions.events.HashtagsEditedListener;
import com.google.gerrit.extensions.events.HeadUpdatedListener;
import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.extensions.events.NewProjectCreatedListener;
import com.google.gerrit.extensions.events.PluginEventListener;
import com.google.gerrit.extensions.events.PrivateStateChangedListener;
import com.google.gerrit.extensions.events.ProjectDeletedListener;
import com.google.gerrit.extensions.events.ProjectIndexedListener;
import com.google.gerrit.extensions.events.ReviewerAddedListener;
import com.google.gerrit.extensions.events.ReviewerDeletedListener;
import com.google.gerrit.extensions.events.RevisionCreatedListener;
import com.google.gerrit.extensions.events.TopicEditedListener;
import com.google.gerrit.extensions.events.UsageDataPublishedListener;
import com.google.gerrit.extensions.events.VoteDeletedListener;
import com.google.gerrit.extensions.events.WorkInProgressStateChangedListener;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.extensions.registration.DynamicMap;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.extensions.validators.CommentValidator;
import com.google.gerrit.extensions.webui.BranchWebLink;
import com.google.gerrit.extensions.webui.DiffWebLink;
import com.google.gerrit.extensions.webui.EditWebLink;
import com.google.gerrit.extensions.webui.FileHistoryWebLink;
import com.google.gerrit.extensions.webui.FileWebLink;
import com.google.gerrit.extensions.webui.ParentWebLink;
import com.google.gerrit.extensions.webui.PatchSetWebLink;
import com.google.gerrit.extensions.webui.ProjectWebLink;
import com.google.gerrit.extensions.webui.ResolveConflictsWebLink;
import com.google.gerrit.extensions.webui.TagWebLink;
import com.google.gerrit.extensions.webui.TopMenu;
import com.google.gerrit.extensions.webui.WebUiPlugin;
import com.google.gerrit.server.AnonymousUser;
import com.google.gerrit.server.CmdLineParserModule;
import com.google.gerrit.server.CreateGroupPermissionSyncer;
import com.google.gerrit.server.DeadlineChecker;
import com.google.gerrit.server.DynamicOptions;
import com.google.gerrit.server.ExceptionHook;
import com.google.gerrit.server.ExceptionHookImpl;
import com.google.gerrit.server.ExternalUser;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.PerformanceMetrics;
import com.google.gerrit.server.PerformanceSummaryLogger;
import com.google.gerrit.server.PluginPushOption;
import com.google.gerrit.server.RequestListener;
import com.google.gerrit.server.ServerStateProvider;
import com.google.gerrit.server.TraceRequestListener;
import com.google.gerrit.server.ValidationOptionsListener;
import com.google.gerrit.server.account.AccountControl;
import com.google.gerrit.server.account.AccountDeactivator;
import com.google.gerrit.server.account.AccountExternalIdCreator;
import com.google.gerrit.server.account.AccountManager;
import com.google.gerrit.server.account.AccountModule;
import com.google.gerrit.server.account.AccountStateProvider;
import com.google.gerrit.server.account.AccountTagProvider;
import com.google.gerrit.server.account.AccountVisibilityProvider;
import com.google.gerrit.server.account.CapabilityCollection;
import com.google.gerrit.server.account.EmailExpander;
import com.google.gerrit.server.account.GroupCacheImpl;
import com.google.gerrit.server.account.GroupControl;
import com.google.gerrit.server.account.GroupIncludeCacheImpl;
import com.google.gerrit.server.account.ServiceUserClassifierImpl;
import com.google.gerrit.server.account.VersionedAuthTokens;
import com.google.gerrit.server.account.VersionedAuthorizedKeys;
import com.google.gerrit.server.account.externalids.ExternalIdModule;
import com.google.gerrit.server.approval.ApprovalsUtil;
import com.google.gerrit.server.auth.AuthBackend;
import com.google.gerrit.server.auth.UniversalAuthBackend;
import com.google.gerrit.server.avatar.AvatarProvider;
import com.google.gerrit.server.cache.CacheRemovalListener;
import com.google.gerrit.server.change.AbandonOp;
import com.google.gerrit.server.change.AccountPatchReviewStore;
import com.google.gerrit.server.change.ChangeFinder;
import com.google.gerrit.server.change.ChangeJson;
import com.google.gerrit.server.change.ChangeKindCacheImpl;
import com.google.gerrit.server.change.ChangePluginDefinedInfoFactory;
import com.google.gerrit.server.change.EmailNewPatchSet;
import com.google.gerrit.server.change.FileInfoJsonModule;
import com.google.gerrit.server.change.FilterIncludedIn;
import com.google.gerrit.server.change.MergeabilityCacheImpl;
import com.google.gerrit.server.change.ReviewerSuggestion;
import com.google.gerrit.server.change.RevisionJson;
import com.google.gerrit.server.comment.CommentContextCacheImpl;
import com.google.gerrit.server.events.EventFactory;
import com.google.gerrit.server.events.EventListener;
import com.google.gerrit.server.events.EventsMetrics;
import com.google.gerrit.server.events.UserScopedEventListener;
import com.google.gerrit.server.extensions.events.AttentionSetObserver;
import com.google.gerrit.server.extensions.events.GitReferenceUpdated;
import com.google.gerrit.server.extensions.webui.UiActions;
import com.google.gerrit.server.flow.FlowService;
import com.google.gerrit.server.git.ChangeMessageModifier;
import com.google.gerrit.server.git.GitModule;
import com.google.gerrit.server.git.MergedByPushOp;
import com.google.gerrit.server.git.MultiProgressMonitor;
import com.google.gerrit.server.git.NotesBranchUtil;
import com.google.gerrit.server.git.PureRevertCache;
import com.google.gerrit.server.git.ReceivePackInitializer;
import com.google.gerrit.server.git.TagCache;
import com.google.gerrit.server.git.TransferConfig;
import com.google.gerrit.server.git.receive.PushOptionsValidator;
import com.google.gerrit.server.git.receive.ReceiveCommitsModule;
import com.google.gerrit.server.git.validators.CommentCountValidator;
import com.google.gerrit.server.git.validators.CommentCumulativeSizeValidator;
import com.google.gerrit.server.git.validators.CommentSizeValidator;
import com.google.gerrit.server.git.validators.CommitValidationInfoListener;
import com.google.gerrit.server.git.validators.CommitValidationListener;
import com.google.gerrit.server.git.validators.MergeValidationListener;
import com.google.gerrit.server.git.validators.MergeValidators;
import com.google.gerrit.server.git.validators.MergeValidators.GroupMergeValidator;
import com.google.gerrit.server.git.validators.MergeValidators.ProjectConfigValidator;
import com.google.gerrit.server.git.validators.OnSubmitValidationListener;
import com.google.gerrit.server.git.validators.OnSubmitValidators;
import com.google.gerrit.server.git.validators.RefOperationValidationListener;
import com.google.gerrit.server.git.validators.RefOperationValidators;
import com.google.gerrit.server.git.validators.UploadValidationListener;
import com.google.gerrit.server.git.validators.UploadValidators;
import com.google.gerrit.server.group.db.GroupDbModule;
import com.google.gerrit.server.index.change.ReindexChangesAfterRefUpdate;
import com.google.gerrit.server.logging.PerformanceLogger;
import com.google.gerrit.server.mail.AutoReplyMailFilter;
import com.google.gerrit.server.mail.ListMailFilter;
import com.google.gerrit.server.mail.MailFilter;
import com.google.gerrit.server.mail.send.FromAddressGenerator;
import com.google.gerrit.server.mail.send.FromAddressGeneratorProvider;
import com.google.gerrit.server.mail.send.MailSoySauceModule;
import com.google.gerrit.server.mail.send.MailSoyTemplateProvider;
import com.google.gerrit.server.mime.FileTypeRegistry;
import com.google.gerrit.server.mime.MimeUtilFileTypeRegistry;
import com.google.gerrit.server.notedb.DeleteZombieCommentsRefs;
import com.google.gerrit.server.notedb.NoteDbModule;
import com.google.gerrit.server.notedb.StoreSubmitRequirementsOp;
import com.google.gerrit.server.patch.DiffFileSizeValidator;
import com.google.gerrit.server.patch.DiffOperationsForCommitValidation;
import com.google.gerrit.server.patch.DiffOperationsImpl;
import com.google.gerrit.server.patch.DiffValidator;
import com.google.gerrit.server.patch.PatchListCacheImpl;
import com.google.gerrit.server.patch.PatchScriptFactory;
import com.google.gerrit.server.patch.PatchScriptFactoryForAutoFix;
import com.google.gerrit.server.patch.PatchSetInfoFactory;
import com.google.gerrit.server.permissions.PermissionCollection;
import com.google.gerrit.server.permissions.SectionSortCache;
import com.google.gerrit.server.plugins.ReloadPluginListener;
import com.google.gerrit.server.project.AccessControlModule;
import com.google.gerrit.server.project.CommentLinkProvider;
import com.google.gerrit.server.project.LockManager;
import com.google.gerrit.server.project.ProjectCacheImpl;
import com.google.gerrit.server.project.ProjectState;
import com.google.gerrit.server.project.PrologRulesWarningValidator;
import com.google.gerrit.server.project.SubmitRequirementConfigValidator;
import com.google.gerrit.server.project.SubmitRequirementsEvaluatorImpl;
import com.google.gerrit.server.project.SubmitRuleEvaluator;
import com.google.gerrit.server.query.approval.ApprovalModule;
import com.google.gerrit.server.query.approval.ApprovalQueryBuilder;
import com.google.gerrit.server.query.change.ChangeData;
import com.google.gerrit.server.query.change.ChangeIsVisibleToPredicate;
import com.google.gerrit.server.query.change.ChangeQueryBuilder;
import com.google.gerrit.server.query.change.ConflictsCacheImpl;
import com.google.gerrit.server.quota.QuotaEnforcer;
import com.google.gerrit.server.restapi.RestModule;
import com.google.gerrit.server.restapi.change.OnPostReview;
import com.google.gerrit.server.restapi.change.SuggestReviewers;
import com.google.gerrit.server.restapi.group.GroupModule;
import com.google.gerrit.server.rules.DefaultSubmitRule.DefaultSubmitRuleModule;
import com.google.gerrit.server.rules.IgnoreSelfApprovalRule.IgnoreSelfApprovalRuleModule;
import com.google.gerrit.server.rules.SubmitRule;
import com.google.gerrit.server.rules.prolog.PrologModule;
import com.google.gerrit.server.ssh.SshAddressesModule;
import com.google.gerrit.server.submit.ConfiguredSubscriptionGraphFactory;
import com.google.gerrit.server.submit.GitModules;
import com.google.gerrit.server.submit.MergeSuperSetComputation;
import com.google.gerrit.server.submit.SubmitStrategy;
import com.google.gerrit.server.submit.SubscriptionGraph;
import com.google.gerrit.server.submitrequirement.predicate.DistinctVotersPredicate;
import com.google.gerrit.server.submitrequirement.predicate.FileEditsPredicate;
import com.google.gerrit.server.submitrequirement.predicate.HasSubmoduleUpdatePredicate;
import com.google.gerrit.server.submitrequirement.predicate.SubmitRequirementLabelExtensionPredicate;
import com.google.gerrit.server.tools.ToolsCatalog;
import com.google.gerrit.server.update.BatchUpdate;
import com.google.gerrit.server.update.LoggingRetryListener;
import com.google.gerrit.server.update.RetryListener;
import com.google.gerrit.server.util.IdGenerator;
import com.google.gerrit.server.util.ThreadLocalRequestContext;
import com.google.gerrit.server.validators.AccountActivationValidationListener;
import com.google.gerrit.server.validators.CustomKeyedValueValidationListener;
import com.google.gerrit.server.validators.GroupCreationValidationListener;
import com.google.gerrit.server.validators.HashtagValidationListener;
import com.google.gerrit.server.validators.OutgoingEmailValidationListener;
import com.google.gerrit.server.validators.ProjectCreationValidationListener;
import com.google.gerrit.server.version.VersionInfoModule;
import com.google.gitiles.blame.cache.BlameCache;
import com.google.gitiles.blame.cache.BlameCacheImpl;
import com.google.inject.Inject;
import com.google.inject.TypeLiteral;
import com.google.inject.internal.UniqueAnnotations;
import com.google.inject.multibindings.OptionalBinder;
import java.util.List;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.transport.PostReceiveHook;
import org.eclipse.jgit.transport.PostUploadHook;
import org.eclipse.jgit.transport.PreUploadHook;

/** Starts global state with standard dependencies. */
public class GerritGlobalModule extends FactoryModule {
  private final Config cfg;

  @Inject
  GerritGlobalModule(@GerritServerConfig Config cfg) {
    this.cfg = cfg;
  }

  @Override
  protected void configure() {
    bind(EmailExpander.class).toProvider(EmailExpanderProvider.class).in(SINGLETON);

    bind(IdGenerator.class);
    bind(BlameCache.class).to(BlameCacheImpl.class);
    install(BatchUpdate.module());
    install(ChangeKindCacheImpl.module());
    install(ChangeFinder.module());
    install(ConflictsCacheImpl.module());
    install(DefaultPreferencesCacheImpl.module());
    install(GroupCacheImpl.module());
    install(GroupIncludeCacheImpl.module());
    install(MergeabilityCacheImpl.module());
    install(ServiceUserClassifierImpl.module());
    install(PatchListCacheImpl.module());
    install(ProjectCacheImpl.module());
    install(DiffOperationsImpl.module());
    install(SectionSortCache.module());
    install(SubmitStrategy.module());
    install(TagCache.module());
    install(PureRevertCache.module());
    install(CommentContextCacheImpl.module());
    install(SubmitRequirementsEvaluatorImpl.module());

    install(new AccessControlModule());
    install(new AccountModule());
    install(new CmdLineParserModule());
    install(new ExternalIdModule());
    install(new GitModule());
    install(new GroupDbModule());
    install(new GroupModule());
    install(new NoteDbModule());
    install(new PrologModule(cfg));
    install(new DefaultSubmitRuleModule());
    install(new IgnoreSelfApprovalRuleModule());
    install(new ReceiveCommitsModule());
    install(new RestModule());
    install(new SshAddressesModule());
    install(new FileInfoJsonModule());
    install(ThreadLocalRequestContext.module());
    install(new ApprovalModule());
    install(new MailSoySauceModule());
    install(new VersionInfoModule());
    install(new SkipCurrentRulesEvaluationOnClosedChangesModule());

    factory(CapabilityCollection.Factory.class);
    factory(ChangeData.AssistedFactory.class);
    factory(ChangeJson.AssistedFactory.class);
    factory(ChangeIsVisibleToPredicate.Factory.class);
    factory(DistinctVotersPredicate.Factory.class);
    factory(SubmitRequirementLabelExtensionPredicate.Factory.class);
    factory(HasSubmoduleUpdatePredicate.Factory.class);
    factory(DeadlineChecker.Factory.class);
    factory(EmailNewPatchSet.Factory.class);
    factory(MultiProgressMonitor.Factory.class);
    factory(PatchScriptFactory.Factory.class);
    factory(PatchScriptFactoryForAutoFix.Factory.class);
    factory(ProjectState.Factory.class);
    factory(RevisionJson.Factory.class);
    factory(ExternalUser.Factory.class);
    bind(PermissionCollection.Factory.class);
    bind(AccountVisibility.class).toProvider(AccountVisibilityProvider.class).in(SINGLETON);
    AccountDefaultDisplayName accountDefaultDisplayName =
        cfg.getEnum("accounts", null, "defaultDisplayName", AccountDefaultDisplayName.FULL_NAME);
    bind(AccountDefaultDisplayName.class).toInstance(accountDefaultDisplayName);
    factory(ProjectOwnerGroupsProvider.Factory.class);
    factory(SubmitRuleEvaluator.Factory.class);
    factory(DeleteZombieCommentsRefs.Factory.class);
    factory(DiffOperationsForCommitValidation.Factory.class);

    bind(AuthBackend.class).to(UniversalAuthBackend.class).in(SINGLETON);
    DynamicSet.setOf(binder(), AuthBackend.class);

    bind(GroupControl.Factory.class).in(SINGLETON);
    bind(GroupControl.GenericFactory.class).in(SINGLETON);

    bind(FileTypeRegistry.class).to(MimeUtilFileTypeRegistry.class);
    bind(ToolsCatalog.class);
    bind(EventFactory.class);
    bind(TransferConfig.class);

    bind(GcConfig.class);
    DynamicSet.setOf(binder(), GerritConfigListener.class);

    bind(ChangeCleanupConfig.class);
    bind(AttentionSetConfig.class);
    bind(AccountDeactivator.class);

    bind(ApprovalsUtil.class);

    bind(FromAddressGenerator.class).toProvider(FromAddressGeneratorProvider.class).in(SINGLETON);
    bind(Boolean.class)
        .annotatedWith(EnablePeerIPInReflogRecord.class)
        .toProvider(EnablePeerIPInReflogRecordProvider.class)
        .in(SINGLETON);

    bind(PatchSetInfoFactory.class);
    bind(IdentifiedUser.GenericFactory.class).in(SINGLETON);
    bind(AccountControl.Factory.class);
    OptionalBinder.newOptionalBinder(binder(), Ticker.class)
        .setDefault()
        .toInstance(Ticker.systemTicker());

    bind(UiActions.class);

    bind(GitReferenceUpdated.class);
    DynamicMap.mapOf(binder(), new TypeLiteral<Cache<?, ?>>() {});
    DynamicSet.setOf(binder(), CacheRemovalListener.class);
    DynamicMap.mapOf(binder(), CapabilityDefinition.class);
    DynamicMap.mapOf(binder(), PluginProjectPermissionDefinition.class);
    DynamicSet.setOf(binder(), GitReferenceUpdatedListener.class);
    DynamicSet.setOf(binder(), GitBatchRefUpdateListener.class);
    DynamicSet.setOf(binder(), ChangeAbandonedListener.class);
    DynamicSet.setOf(binder(), ChangeDeletedListener.class);
    DynamicSet.setOf(binder(), CommentAddedListener.class);
    DynamicSet.setOf(binder(), HashtagsEditedListener.class);
    DynamicSet.setOf(binder(), CustomKeyedValuesEditedListener.class);
    DynamicSet.setOf(binder(), ChangeMergedListener.class);
    bind(ChangeMergedListener.class)
        .annotatedWith(Exports.named("CreateGroupPermissionSyncer"))
        .to(CreateGroupPermissionSyncer.class);

    DynamicSet.setOf(binder(), ChangeRestoredListener.class);
    DynamicSet.setOf(binder(), ChangeRevertedListener.class);
    DynamicSet.setOf(binder(), PrivateStateChangedListener.class);
    DynamicSet.setOf(binder(), ReviewerAddedListener.class);
    DynamicSet.setOf(binder(), ReviewerDeletedListener.class);
    DynamicSet.setOf(binder(), VoteDeletedListener.class);
    DynamicSet.setOf(binder(), WorkInProgressStateChangedListener.class);
    DynamicSet.setOf(binder(), RevisionCreatedListener.class);
    DynamicSet.setOf(binder(), TopicEditedListener.class);
    DynamicSet.setOf(binder(), AgreementSignupListener.class);
    DynamicSet.setOf(binder(), PluginEventListener.class);
    DynamicSet.setOf(binder(), ReceivePackInitializer.class);
    DynamicSet.setOf(binder(), PostReceiveHook.class);
    DynamicSet.setOf(binder(), PreUploadHook.class);
    DynamicSet.setOf(binder(), PostUploadHook.class);
    DynamicSet.setOf(binder(), AccountActivationListener.class);
    DynamicSet.setOf(binder(), AccountIndexedListener.class);
    DynamicSet.setOf(binder(), ChangeIndexedListener.class);
    DynamicSet.setOf(binder(), GroupIndexedListener.class);
    DynamicSet.setOf(binder(), ProjectIndexedListener.class);
    DynamicSet.setOf(binder(), NewProjectCreatedListener.class);
    DynamicSet.setOf(binder(), ProjectDeletedListener.class);
    DynamicSet.setOf(binder(), GarbageCollectorListener.class);
    DynamicSet.setOf(binder(), HeadUpdatedListener.class);
    DynamicSet.setOf(binder(), UsageDataPublishedListener.class);
    DynamicSet.bind(binder(), GitBatchRefUpdateListener.class)
        .to(ReindexChangesAfterRefUpdate.class);
    DynamicSet.bind(binder(), GitReferenceUpdatedListener.class)
        .to(ProjectConfigEntry.UpdateChecker.class);
    DynamicSet.setOf(binder(), EventListener.class);
    DynamicSet.bind(binder(), EventListener.class).to(EventsMetrics.class);
    DynamicSet.setOf(binder(), UserScopedEventListener.class);
    DynamicSet.setOf(binder(), CommitValidationListener.class);
    DynamicSet.bind(binder(), CommitValidationListener.class)
        .to(SubmitRequirementConfigValidator.class);
    DynamicSet.bind(binder(), CommitValidationListener.class).to(PrologRulesWarningValidator.class);
    DynamicSet.setOf(binder(), CommitValidationInfoListener.class);
    DynamicSet.setOf(binder(), CommentValidator.class);
    DynamicSet.setOf(binder(), DiffValidator.class);
    DynamicSet.bind(binder(), DiffValidator.class).to(DiffFileSizeValidator.class);
    DynamicSet.setOf(binder(), ChangeMessageModifier.class);
    DynamicSet.setOf(binder(), RefOperationValidationListener.class);
    DynamicSet.setOf(binder(), OnSubmitValidationListener.class);
    DynamicSet.setOf(binder(), MergeValidationListener.class);
    DynamicSet.setOf(binder(), ProjectCreationValidationListener.class);
    DynamicSet.setOf(binder(), GroupCreationValidationListener.class);
    DynamicSet.setOf(binder(), HashtagValidationListener.class);
    DynamicSet.setOf(binder(), OutgoingEmailValidationListener.class);
    DynamicSet.setOf(binder(), AccountActivationValidationListener.class);
    DynamicSet.setOf(binder(), CustomKeyedValueValidationListener.class);
    DynamicItem.itemOf(binder(), AvatarProvider.class);
    DynamicSet.setOf(binder(), LifecycleListener.class);
    DynamicSet.setOf(binder(), TopMenu.class);
    DynamicMap.mapOf(binder(), DownloadScheme.class);
    DynamicMap.mapOf(binder(), DownloadCommand.class);
    DynamicMap.mapOf(binder(), CloneCommand.class);
    DynamicMap.mapOf(binder(), ReviewerSuggestion.class);
    DynamicSet.bind(binder(), GerritConfigListener.class)
        .toInstance(SuggestReviewers.configListener());
    DynamicSet.setOf(binder(), ExternalIncludedIn.class);
    DynamicSet.setOf(binder(), FilterIncludedIn.class);
    DynamicMap.mapOf(binder(), ProjectConfigEntry.class);
    DynamicSet.setOf(binder(), PushOptionsValidator.class);
    DynamicSet.setOf(binder(), PluginPushOption.class);
    DynamicSet.setOf(binder(), PatchSetWebLink.class);
    DynamicSet.setOf(binder(), ResolveConflictsWebLink.class);
    DynamicSet.setOf(binder(), ParentWebLink.class);
    DynamicSet.setOf(binder(), FileWebLink.class);
    DynamicSet.setOf(binder(), FileHistoryWebLink.class);
    DynamicSet.setOf(binder(), DiffWebLink.class);
    DynamicSet.setOf(binder(), EditWebLink.class);
    DynamicSet.setOf(binder(), ProjectWebLink.class);
    DynamicSet.setOf(binder(), BranchWebLink.class);
    DynamicSet.setOf(binder(), TagWebLink.class);
    DynamicMap.mapOf(binder(), OAuthLoginProvider.class);
    DynamicItem.itemOf(binder(), OAuthTokenEncrypter.class);
    DynamicSet.setOf(binder(), AccountExternalIdCreator.class);
    DynamicSet.setOf(binder(), WebUiPlugin.class);
    DynamicItem.itemOf(binder(), AccountPatchReviewStore.class);
    DynamicSet.setOf(binder(), ActionVisitor.class);
    DynamicItem.itemOf(binder(), MergeSuperSetComputation.class);
    DynamicItem.itemOf(binder(), LockManager.class);
    DynamicSet.setOf(binder(), SubmitRule.class);
    DynamicSet.setOf(binder(), SubmitRequirement.class);
    DynamicSet.setOf(binder(), QuotaEnforcer.class);
    DynamicSet.setOf(binder(), PerformanceLogger.class);
    DynamicSet.bind(binder(), PerformanceLogger.class).to(PerformanceMetrics.class);
    DynamicSet.bind(binder(), PerformanceLogger.class).to(PerformanceSummaryLogger.class);
    DynamicSet.setOf(binder(), RequestListener.class);
    DynamicSet.bind(binder(), RequestListener.class).to(TraceRequestListener.class);
    DynamicSet.setOf(binder(), ExceptionHook.class);
    DynamicSet.bind(binder(), ExceptionHook.class).to(ExceptionHookImpl.class);
    DynamicSet.setOf(binder(), MailSoyTemplateProvider.class);
    DynamicSet.setOf(binder(), OnPostReview.class);
    DynamicSet.setOf(binder(), ServerStateProvider.class);
    DynamicSet.setOf(binder(), AccountStateProvider.class);
    DynamicMap.mapOf(binder(), AccountTagProvider.class);
    DynamicSet.setOf(binder(), AttentionSetListener.class);
    DynamicSet.setOf(binder(), ValidationOptionsListener.class);
    DynamicSet.setOf(binder(), RetryListener.class);
    DynamicSet.bind(binder(), RetryListener.class).to(LoggingRetryListener.class);
    DynamicItem.itemOf(binder(), FlowService.class);

    DynamicMap.mapOf(binder(), MailFilter.class);
    bind(MailFilter.class).annotatedWith(Exports.named("ListMailFilter")).to(ListMailFilter.class);
    bind(AutoReplyMailFilter.class)
        .annotatedWith(Exports.named("AutoReplyMailFilter"))
        .to(AutoReplyMailFilter.class);

    factory(UploadValidators.Factory.class);
    DynamicSet.setOf(binder(), UploadValidationListener.class);

    bind(CommentValidator.class)
        .annotatedWith(Exports.named(CommentCountValidator.class.getSimpleName()))
        .to(CommentCountValidator.class);
    bind(CommentValidator.class)
        .annotatedWith(Exports.named(CommentSizeValidator.class.getSimpleName()))
        .to(CommentSizeValidator.class);
    bind(CommentValidator.class)
        .annotatedWith(Exports.named(CommentCumulativeSizeValidator.class.getSimpleName()))
        .to(CommentCumulativeSizeValidator.class);

    DynamicMap.mapOf(binder(), DynamicOptions.DynamicBean.class);
    DynamicMap.mapOf(binder(), ChangeQueryBuilder.ChangeOperatorFactory.class);
    DynamicMap.mapOf(binder(), ChangeQueryBuilder.ChangeHasOperandFactory.class);
    DynamicMap.mapOf(binder(), ChangeQueryBuilder.ChangeIsOperandFactory.class);
    DynamicMap.mapOf(binder(), ApprovalQueryBuilder.UserInOperandFactory.class);
    DynamicSet.setOf(binder(), ChangePluginDefinedInfoFactory.class);

    install(new GitwebConfig.LegacyModule(cfg));

    bind(AnonymousUser.class);

    factory(AbandonOp.Factory.class);
    factory(GroupMergeValidator.Factory.class);
    factory(RefOperationValidators.Factory.class);
    factory(OnSubmitValidators.Factory.class);
    factory(MergeValidators.Factory.class);
    factory(ProjectConfigValidator.Factory.class);
    factory(NotesBranchUtil.Factory.class);
    factory(MergedByPushOp.Factory.class);
    factory(GitModules.Factory.class);
    factory(VersionedAuthorizedKeys.Factory.class);
    factory(VersionedAuthTokens.Factory.class);
    factory(StoreSubmitRequirementsOp.Factory.class);
    factory(FileEditsPredicate.Factory.class);

    bind(AccountManager.class);
    bind(SubscriptionGraph.Factory.class).to(ConfiguredSubscriptionGraphFactory.class);

    bind(new TypeLiteral<List<CommentLinkInfo>>() {}).toProvider(CommentLinkProvider.class);
    DynamicSet.bind(binder(), GerritConfigListener.class).to(CommentLinkProvider.class);

    bind(ReloadPluginListener.class)
        .annotatedWith(UniqueAnnotations.create())
        .to(PluginConfigFactory.class);

    bind(AttentionSetObserver.class);
  }
}
