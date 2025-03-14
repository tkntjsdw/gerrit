// Copyright (C) 2008 The Android Open Source Project
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

package com.google.gerrit.server.submit;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.gerrit.server.experiments.ExperimentFeaturesConstants.GERRIT_BACKEND_FEATURE_ALWAYS_REJECT_IMPLICIT_MERGES_ON_MERGE;
import static com.google.gerrit.server.experiments.ExperimentFeaturesConstants.GERRIT_BACKEND_FEATURE_CHECK_IMPLICIT_MERGES_ON_MERGE;
import static com.google.gerrit.server.experiments.ExperimentFeaturesConstants.GERRIT_BACKEND_FEATURE_REJECT_IMPLICIT_MERGES_ON_MERGE;
import static com.google.gerrit.server.project.ProjectCache.illegalState;
import static com.google.gerrit.server.update.RetryableAction.ActionType.INDEX_QUERY;
import static com.google.gerrit.server.update.context.RefUpdateContext.RefUpdateType.MERGE_CHANGE;
import static java.util.Comparator.comparing;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toSet;

import com.github.rholder.retry.Attempt;
import com.github.rholder.retry.RetryListener;
import com.google.auto.value.AutoValue;
import com.google.common.base.Joiner;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.MultimapBuilder;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;
import com.google.common.flogger.FluentLogger;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.entities.BooleanProjectConfig;
import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.Change.Status;
import com.google.gerrit.entities.PatchSet;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.SubmissionId;
import com.google.gerrit.entities.SubmitRecord;
import com.google.gerrit.entities.SubmitRequirement;
import com.google.gerrit.entities.SubmitRequirementResult;
import com.google.gerrit.entities.SubmitTypeRecord;
import com.google.gerrit.exceptions.MergeUpdateException;
import com.google.gerrit.exceptions.StorageException;
import com.google.gerrit.extensions.api.changes.NotifyHandling;
import com.google.gerrit.extensions.api.changes.SubmitInput;
import com.google.gerrit.extensions.client.SubmitType;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.extensions.restapi.ResourceNotFoundException;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.git.LockFailureException;
import com.google.gerrit.metrics.Counter0;
import com.google.gerrit.metrics.Description;
import com.google.gerrit.metrics.MetricMaker;
import com.google.gerrit.server.ChangeMessagesUtil;
import com.google.gerrit.server.ChangeUtil;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.InternalUser;
import com.google.gerrit.server.change.NotifyResolver;
import com.google.gerrit.server.config.ConfigUtil;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.gerrit.server.experiments.ExperimentFeatures;
import com.google.gerrit.server.git.CodeReviewCommit;
import com.google.gerrit.server.git.MergeTip;
import com.google.gerrit.server.git.validators.MergeValidationException;
import com.google.gerrit.server.git.validators.MergeValidators;
import com.google.gerrit.server.logging.RequestId;
import com.google.gerrit.server.logging.TraceContext;
import com.google.gerrit.server.notedb.ChangeNotes;
import com.google.gerrit.server.notedb.StoreSubmitRequirementsOp;
import com.google.gerrit.server.permissions.ChangePermission;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.gerrit.server.project.NoSuchProjectException;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.project.SubmitRuleOptions;
import com.google.gerrit.server.query.change.ChangeData;
import com.google.gerrit.server.query.change.InternalChangeQuery;
import com.google.gerrit.server.submit.MergeOpRepoManager.OpenBranch;
import com.google.gerrit.server.submit.MergeOpRepoManager.OpenRepo;
import com.google.gerrit.server.update.BatchUpdate;
import com.google.gerrit.server.update.BatchUpdateOp;
import com.google.gerrit.server.update.BatchUpdates;
import com.google.gerrit.server.update.ChangeContext;
import com.google.gerrit.server.update.RetryHelper;
import com.google.gerrit.server.update.SubmissionExecutor;
import com.google.gerrit.server.update.SubmissionListener;
import com.google.gerrit.server.update.SuperprojectUpdateOnSubmission;
import com.google.gerrit.server.update.UpdateException;
import com.google.gerrit.server.update.context.RefUpdateContext;
import com.google.gerrit.server.util.time.TimeUtil;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import java.io.IOException;
import java.time.Instant;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

/**
 * Merges changes in submission order into a single branch.
 *
 * <p>Branches are reduced to the minimum number of heads needed to merge everything. This allows
 * commits to be entered into the queue in any order (such as ancestors before descendants) and only
 * the most recent commit on any line of development will be merged. All unmerged commits along a
 * line of development must be in the submission queue in order to merge the tip of that line.
 *
 * <p>Conflicts are handled by discarding the entire line of development and marking it as
 * conflicting, even if an earlier commit along that same line can be merged cleanly.
 */
public class MergeOp implements AutoCloseable {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static final SubmitRuleOptions SUBMIT_RULE_OPTIONS = SubmitRuleOptions.builder().build();
  private static final SubmitRuleOptions SUBMIT_RULE_OPTIONS_ALLOW_CLOSED =
      SUBMIT_RULE_OPTIONS.toBuilder().recomputeOnClosedChanges(true).build();

  /**
   * For each individual change in merge set aggregates issues and other details throughout the
   * merge process.
   */
  public static class CommitStatus {
    private final ImmutableMap<Change.Id, ChangeData> changes;
    private final ImmutableSetMultimap<BranchNameKey, Change.Id> byBranch;
    private final Map<Change.Id, CodeReviewCommit> commits;
    private final ListMultimap<Change.Id, String> problems;
    private final Set<SimpleImmutableEntry<Project.NameKey, BranchNameKey>> implicitMergeProblems;

    private final boolean allowClosed;

    private CommitStatus(ChangeSet cs, boolean allowClosed) {
      checkArgument(
          !cs.furtherHiddenChanges(), "CommitStatus must not be called with hidden changes");
      changes = cs.changesById();
      ImmutableSetMultimap.Builder<BranchNameKey, Change.Id> bb = ImmutableSetMultimap.builder();
      for (ChangeData cd : cs.changes()) {
        bb.put(cd.change().getDest(), cd.getId());
      }
      byBranch = bb.build();
      commits = new HashMap<>();
      problems = MultimapBuilder.treeKeys(comparing(Change.Id::get)).arrayListValues(1).build();
      implicitMergeProblems = new HashSet<>();
      this.allowClosed = allowClosed;
    }

    public ImmutableSet<Change.Id> getChangeIds() {
      return changes.keySet();
    }

    public ImmutableSet<Change.Id> getChangeIds(BranchNameKey branch) {
      return byBranch.get(branch);
    }

    public CodeReviewCommit get(Change.Id changeId) {
      return commits.get(changeId);
    }

    public void put(CodeReviewCommit c) {
      commits.put(c.change().getId(), c);
    }

    public void problem(Change.Id id, String problem) {
      problems.put(id, problem);
    }

    public void logProblem(Change.Id id, Throwable t) {
      String msg = "Error reading change";
      logger.atSevere().withCause(t).log("%s %s", msg, id);
      problems.put(id, msg);
    }

    public void logProblem(Change.Id id, String msg) {
      logger.atSevere().log("%s %s", msg, id);
      problems.put(id, msg);
    }

    public void addImplicitMerge(Project.NameKey projectName, BranchNameKey branchName) {
      implicitMergeProblems.add(new SimpleImmutableEntry<>(projectName, branchName));
    }

    public boolean isOk() {
      return problems.isEmpty() && implicitMergeProblems.isEmpty();
    }

    public List<SubmitRecord> getSubmitRecords(Change.Id id) {
      // Use the cached submit records from the original ChangeData in the input
      // ChangeSet, which were checked earlier in the integrate process. Even in
      // the case of a race where the submit records may have changed, it makes
      // more sense to store the original results of the submit rule evaluator
      // than to fail at this point.
      //
      // However, do NOT expose that ChangeData directly, as it is way out of
      // date by this point.
      ChangeData cd = requireNonNull(changes.get(id), () -> String.format("ChangeData for %s", id));
      return requireNonNull(
          cd.submitRecords(submitRuleOptions(allowClosed)),
          "getSubmitRecord only valid after submit rules are evalutated");
    }

    public void maybeFailVerbose() throws ResourceConflictException {
      if (isOk()) {
        return;
      }
      String msg =
          "Failed to submit "
              + changes.size()
              + " change"
              + (changes.size() > 1 ? "s" : "")
              + " due to the following problems:\n";
      List<String> ps = new ArrayList<>(problems.keySet().size());
      for (Change.Id id : problems.keySet()) {
        ps.add("Change " + id + ": " + Joiner.on("; ").join(problems.get(id)));
      }
      if (ps.isEmpty()) {
        // An implicit merge can be also detected when there are another problems with changes(e.g.
        // the parent change is deleted). It can confuse the user if gerrit reports both the correct
        // problem and implicit merge problem at the same time - so report implicit merge problem
        // only if no other problems are reported.
        for (SimpleImmutableEntry<Project.NameKey, BranchNameKey> projectBranch :
            implicitMergeProblems) {
          // TODO(dmfilippov): Make message more clear to the user and add the exact change id.
          ps.add(
              String.format(
                  "submit makes implicit merge to the branch %s of the project %s from some other "
                      + "branch",
                  projectBranch.getValue().shortName(), projectBranch.getKey().get()));
        }
      }
      throw new ResourceConflictException(msg + Joiner.on('\n').join(ps));
    }

    public void maybeFail(String msgPrefix) throws ResourceConflictException {
      if (isOk()) {
        return;
      }
      StringBuilder msg = new StringBuilder(msgPrefix).append(" of change");
      Set<Change.Id> ids = problems.keySet();
      if (ids.size() == 1) {
        msg.append(" ").append(ids.iterator().next());
      } else {
        msg.append("s ").append(Joiner.on(", ").join(ids));
      }
      throw new ResourceConflictException(msg.toString());
    }
  }

  private final ChangeMessagesUtil cmUtil;
  private final BatchUpdate.Factory batchUpdateFactory;
  private final BatchUpdates batchUpdates;
  private final InternalUser.Factory internalUserFactory;
  private final MergeSuperSet mergeSuperSet;
  private final MergeValidators.Factory mergeValidatorsFactory;
  private final Provider<InternalChangeQuery> queryProvider;
  private final SubmitStrategyFactory submitStrategyFactory;
  private final SubscriptionGraph.Factory subscriptionGraphFactory;
  private final SubmoduleCommits.Factory submoduleCommitsFactory;
  private final ImmutableList<SubmissionListener> superprojectUpdateSubmissionListeners;
  private final Provider<MergeOpRepoManager> ormProvider;
  private final NotifyResolver notifyResolver;
  private final RetryHelper retryHelper;
  private final ChangeData.Factory changeDataFactory;
  private final StoreSubmitRequirementsOp.Factory storeSubmitRequirementsOpFactory;
  private final MergeMetrics mergeMetrics;
  private final PermissionBackend permissionBackend;

  // Changes that were updated by this MergeOp.
  private final Map<Change.Id, Change> updatedChanges;

  private final ExperimentFeatures experimentFeatures;

  private final ProjectCache projectCache;
  private final long hasImplicitMergeTimeoutSeconds;

  private Instant ts;
  private SubmissionId submissionId;
  private IdentifiedUser caller;

  private MergeOpRepoManager orm;
  private CommitStatus commitStatus;
  private SubmitInput submitInput;
  private NotifyResolver.Result notify;
  private ImmutableSet<Project.NameKey> projects;
  private boolean dryrun;
  private TopicMetrics topicMetrics;

  @Inject
  MergeOp(
      ChangeMessagesUtil cmUtil,
      BatchUpdate.Factory batchUpdateFactory,
      BatchUpdates batchUpdates,
      InternalUser.Factory internalUserFactory,
      MergeSuperSet mergeSuperSet,
      MergeValidators.Factory mergeValidatorsFactory,
      Provider<InternalChangeQuery> queryProvider,
      SubmitStrategyFactory submitStrategyFactory,
      SubmoduleCommits.Factory submoduleCommitsFactory,
      SubscriptionGraph.Factory subscriptionGraphFactory,
      @SuperprojectUpdateOnSubmission
          ImmutableList<SubmissionListener> superprojectUpdateSubmissionListeners,
      Provider<MergeOpRepoManager> ormProvider,
      NotifyResolver notifyResolver,
      TopicMetrics topicMetrics,
      RetryHelper retryHelper,
      ChangeData.Factory changeDataFactory,
      StoreSubmitRequirementsOp.Factory storeSubmitRequirementsOpFactory,
      MergeMetrics mergeMetrics,
      ProjectCache projectCache,
      ExperimentFeatures experimentFeatures,
      @GerritServerConfig Config config,
      PermissionBackend permissionBackend) {
    this.cmUtil = cmUtil;
    this.batchUpdateFactory = batchUpdateFactory;
    this.batchUpdates = batchUpdates;
    this.internalUserFactory = internalUserFactory;
    this.mergeSuperSet = mergeSuperSet;
    this.mergeValidatorsFactory = mergeValidatorsFactory;
    this.queryProvider = queryProvider;
    this.submitStrategyFactory = submitStrategyFactory;
    this.submoduleCommitsFactory = submoduleCommitsFactory;
    this.subscriptionGraphFactory = subscriptionGraphFactory;
    this.superprojectUpdateSubmissionListeners = superprojectUpdateSubmissionListeners;
    this.ormProvider = ormProvider;
    this.notifyResolver = notifyResolver;
    this.retryHelper = retryHelper;
    this.topicMetrics = topicMetrics;
    this.changeDataFactory = changeDataFactory;
    this.updatedChanges = new HashMap<>();
    this.storeSubmitRequirementsOpFactory = storeSubmitRequirementsOpFactory;
    this.mergeMetrics = mergeMetrics;
    this.projectCache = projectCache;
    this.experimentFeatures = experimentFeatures;
    // Undocumented - experimental, can be removed.
    hasImplicitMergeTimeoutSeconds =
        ConfigUtil.getTimeUnit(
            config, "change", null, "implicitMergeCalculationTimeout", 60, TimeUnit.SECONDS);
    this.permissionBackend = permissionBackend;
  }

  @Override
  public void close() {
    if (orm != null) {
      orm.close();
    }
  }

  /**
   * Check that SRs are fulfilled or throw otherwise
   *
   * @param cd change that is being checked
   * @throws ResourceConflictException the exception that is thrown if the SR is not fulfilled
   */
  public static void checkSubmitRequirements(ChangeData cd) throws ResourceConflictException {
    PatchSet patchSet = cd.currentPatchSet();
    if (patchSet == null) {
      throw new ResourceConflictException("missing current patch set for change " + cd.getId());
    }
    Map<SubmitRequirement, SubmitRequirementResult> srResults =
        cd.submitRequirementsIncludingLegacy();
    if (srResults.values().stream().allMatch(SubmitRequirementResult::fulfilled)) {
      return;
    } else if (srResults.isEmpty()) {
      throw new IllegalStateException(
          String.format(
              "Submit requirement results for change '%s' and patchset '%s' "
                  + "are empty in project '%s'",
              cd.getId(), patchSet.id(), cd.change().getProject().get()));
    }

    for (SubmitRequirementResult srResult : srResults.values()) {
      switch (srResult.status()) {
        case SATISFIED, NOT_APPLICABLE, OVERRIDDEN, FORCED -> {}
        case ERROR ->
            throw new ResourceConflictException(
                String.format(
                    "submit requirement '%s' has an error: %s",
                    srResult.submitRequirement().name(), srResult.errorMessage().orElse("")));
        case UNSATISFIED ->
            throw new ResourceConflictException(
                String.format(
                    "submit requirement '%s' is unsatisfied.",
                    srResult.submitRequirement().name()));
        default ->
            throw new IllegalStateException(
                String.format(
                    "Unexpected submit requirement status %s for %s in %s",
                    srResult.status().name(),
                    patchSet.id().getId(),
                    cd.change().getProject().get()));
      }
    }
    throw new IllegalStateException();
  }

  private static SubmitRuleOptions submitRuleOptions(boolean allowClosed) {
    return allowClosed ? SUBMIT_RULE_OPTIONS_ALLOW_CLOSED : SUBMIT_RULE_OPTIONS;
  }

  private static List<SubmitRecord> getSubmitRecords(ChangeData cd) {
    return cd.submitRecords(submitRuleOptions(/* allowClosed= */ false));
  }

  /** A problem preventing merge and change on which it occurred. */
  @AutoValue
  public abstract static class ChangeProblem {
    public abstract Change.Id getChangeId();

    public abstract String getProblem();

    public static ChangeProblem create(Change.Id changeId, String problem) {
      return new AutoValue_MergeOp_ChangeProblem(changeId, problem);
    }
  }

  private static void addProblemForChange(
      Change.Id triggeringChangeId,
      ChangeData cd,
      boolean allowMerged,
      PermissionBackend permissionBackend,
      CurrentUser caller,
      ImmutableList.Builder<ChangeProblem> problems) {
    try {
      Set<ChangePermission> can =
          permissionBackend
              .user(caller.getRealUser())
              .change(cd)
              .test(
                  EnumSet.of(
                      ChangePermission.READ, ChangePermission.SUBMIT, ChangePermission.SUBMIT_AS));
      if (!can.contains(ChangePermission.READ)) {
        // The READ permission should already be handled during generation of ChangeSet, however
        // MergeSuperSetComputation is an interface and on API level doesn't guarantee that this
        // have been verified for all changes. Additionally, this protects against potential
        // issues due to staleness.
        if (triggeringChangeId.get() != cd.getId().get()) {
          logger.atFine().log(
              "Change %d cannot be submitted by user %s because it depends on change %d which the"
                  + "user cannot read",
              triggeringChangeId.get(), caller.getRealUser().getLoggableName(), cd.getId().get());
          problems.add(
              ChangeProblem.create(
                  cd.getId(),
                  String.format(
                      "Change %d depends on other hidden changes", triggeringChangeId.get())));
        } else {
          logger.atFine().log(
              "Change %d cannot be submitted by user %s because they don't have READ permission",
              triggeringChangeId.get(), caller.getRealUser().getLoggableName());
          problems.add(
              ChangeProblem.create(
                  cd.getId(), String.format("Change %d is not visible", triggeringChangeId.get())));
        }
        return;
      }
      if (!can.contains(ChangePermission.SUBMIT)) {
        if (triggeringChangeId.get() != cd.getId().get()) {
          logger.atFine().log(
              "Change %d cannot be submitted by user %s because it depends on change %d which the"
                  + "user cannot submit",
              triggeringChangeId.get(), caller.getRealUser().getLoggableName(), cd.getId().get());
        } else {
          logger.atFine().log(
              "Change %d cannot be submitted by user %s because they don't have SUBMIT permission",
              triggeringChangeId.get(), caller.getRealUser().getLoggableName());
        }
        problems.add(
            ChangeProblem.create(
                cd.getId(),
                String.format("Insufficient permission to submit change %d", cd.getId().get())));
        return;
      }
      if (caller.isImpersonating()) {
        if (!permissionBackend.user(caller).change(cd).test(ChangePermission.READ)) {
          if (triggeringChangeId.get() != cd.getId().get()) {
            logger.atFine().log(
                "Change %d cannot be submitted by user %s on behalf of user %s because it depends"
                    + " on change %d which the on-behalf-of user does not have READ permission for",
                triggeringChangeId.get(),
                caller.getRealUser().getLoggableName(),
                caller.getLoggableName(),
                cd.getId().get());
          } else {
            logger.atFine().log(
                "Change %d cannot be submitted by user %s on behalf of user %s because the"
                    + " on-behalf-of user does not have READ permission",
                triggeringChangeId.get(),
                caller.getRealUser().getLoggableName(),
                caller.getLoggableName());
          }
          problems.add(
              ChangeProblem.create(
                  cd.getId(),
                  String.format(
                      "On-behalf-of user %s lacks permission to read change %d",
                      caller.getLoggableName(), cd.getId().get())));
          return;
        }
        if (!can.contains(ChangePermission.SUBMIT_AS)) {
          if (triggeringChangeId.get() != cd.getId().get()) {
            logger.atFine().log(
                "Change %d cannot be submitted by user %s on behalf of user %s because it depends"
                    + " on change %d which the user does not have SUBMIT_AS permission for",
                triggeringChangeId.get(),
                caller.getRealUser().getLoggableName(),
                caller.getLoggableName(),
                cd.getId().get());
          } else {
            logger.atFine().log(
                "Change %d cannot be submitted by user %s on behalf of user %s because they do not"
                    + " have SUBMIT_AS permission",
                triggeringChangeId.get(),
                caller.getRealUser().getLoggableName(),
                caller.getLoggableName());
          }
          problems.add(
              ChangeProblem.create(
                  cd.getId(),
                  String.format(
                      "Insufficient permission to submit change %d on behalf of user %s",
                      cd.getId().get(), caller.getLoggableName())));
          return;
        }
      }
      if (!cd.change().isNew()) {
        if (!(cd.change().isMerged() && allowMerged)) {
          problems.add(
              ChangeProblem.create(
                  cd.getId(),
                  String.format(
                      "Change %d is %s", cd.getId().get(), ChangeUtil.status(cd.change()))));
          return;
        }
      }
      if (cd.change().isWorkInProgress()) {
        problems.add(
            ChangeProblem.create(
                cd.getId(),
                String.format("Change %d is marked work in progress", cd.getId().get())));
        return;
      }
      try {
        checkSubmitRequirements(cd);
      } catch (ResourceConflictException e) {
        // ResourceConflictException is thrown means submit requirement is not fulfilled.
        problems.add(
            ChangeProblem.create(
                cd.getId(),
                triggeringChangeId.equals(cd.getId())
                    ? String.format("Change %s is not ready: %s", cd.getId(), e.getMessage())
                    : String.format(
                        "Change %s must be submitted with change %s but %s is not ready: %s",
                        triggeringChangeId, cd.getId(), cd.getId(), e.getMessage())));
      }
    } catch (StorageException | PermissionBackendException e) {
      String msg = "Error checking submit rules for change";
      logger.atWarning().withCause(e).log("%s %s", msg, triggeringChangeId);
      problems.add(ChangeProblem.create(cd.getId(), msg));
    }
  }

  /**
   * Returns a list of messages describing what prevents the current change from being submitted.
   *
   * <p>The method checks all changes in the {@code cs} for their current status, submitability and
   * permissions and returns one change per change in the set that can't be submitted.
   *
   * @param triggeringChange Change for which merge/submit action was initiated
   * @param cs Set of changes that the current change depends on
   * @param allowMerged True if change being already merged is not a problem to be reported
   * @param permissionBackend Interface for checking user ACLs
   * @param caller the identity of the user that is recorded as the one performing the merge. In
   *     case of impersonation {@code caller.getRealUser()} contains the user triggering the merge.
   * @return List of problems preventing merge
   */
  public static ImmutableList<ChangeProblem> checkCommonSubmitProblems(
      Change triggeringChange,
      ChangeSet cs,
      boolean allowMerged,
      PermissionBackend permissionBackend,
      CurrentUser caller) {
    ImmutableList.Builder<ChangeProblem> problems = ImmutableList.builder();
    if (cs.furtherHiddenChanges()) {
      logger.atFine().log(
          "Change %d cannot be submitted by user %s because it depends on hidden changes: %s",
          triggeringChange.getId().get(),
          caller.getRealUser().getLoggableName(),
          cs.nonVisibleChanges());
      problems.add(
          ChangeProblem.create(
              triggeringChange.getId(),
              String.format(
                  "Change %d depends on other hidden changes", triggeringChange.getId().get())));
    }
    for (ChangeData cd : cs.changes()) {
      addProblemForChange(
          triggeringChange.getId(), cd, allowMerged, permissionBackend, caller, problems);
    }
    return problems.build();
  }

  private void checkSubmitRulesAndState(Change triggeringChange, ChangeSet cs, boolean allowMerged)
      throws ResourceConflictException {
    checkCommonSubmitProblems(triggeringChange, cs, allowMerged, permissionBackend, caller).stream()
        .forEach(cp -> commitStatus.problem(cp.getChangeId(), cp.getProblem()));
    commitStatus.maybeFailVerbose();
    mergeMetrics.countChangesThatWereSubmittedWithRebaserApproval(cs);
  }

  private void bypassSubmitRulesAndRequirements(ChangeSet cs) {
    checkArgument(
        !cs.furtherHiddenChanges(), "cannot bypass submit rules for topic with hidden change");
    for (ChangeData cd : cs.changes()) {
      Change change = cd.change();
      if (change == null) {
        throw new StorageException("Change not found");
      }
      if (change.isClosed()) {
        // No need to check submit rules if the change is closed.
        continue;
      }
      List<SubmitRecord> records = new ArrayList<>(getSubmitRecords(cd));
      SubmitRecord forced = new SubmitRecord();
      forced.status = SubmitRecord.Status.FORCED;
      records.add(forced);
      cd.setSubmitRecords(submitRuleOptions(/* allowClosed= */ false), records);

      // Also bypass submit requirements. Mark them as forced.
      Map<SubmitRequirement, SubmitRequirementResult> forcedSRs =
          cd.submitRequirementsIncludingLegacy().entrySet().stream()
              .collect(
                  Collectors.toMap(
                      Map.Entry::getKey,
                      entry -> entry.getValue().toBuilder().forced(Optional.of(true)).build()));
      cd.setSubmitRequirements(forcedSRs);
    }
  }

  /**
   * Merges the given change.
   *
   * <p>Depending on the server configuration, more changes may be affected, e.g. by submission of a
   * topic or via superproject subscriptions. All affected changes are integrated using the projects
   * integration strategy.
   *
   * @param change the change to be merged.
   * @param caller the identity of the user that is recorded as the one performing the merge. In
   *     case of impersonation {@code caller.getRealUser()} contains the user triggering the merge.
   * @param checkSubmitRules whether submit rules and submit requirements should be evaluated.
   * @param submitInput parameters regarding the merge
   * @param dryrun if true, this includes calculating all projects affected by the submission,
   *     checking for possible submission problems (ACLs, merge conflicts, etc) but not the merge
   *     itself.
   * @throws RestApiException if an error occurred.
   * @throws PermissionBackendException if permissions can't be checked
   * @throws IOException an error occurred reading from NoteDb.
   * @return the merged change
   */
  // TODO: dryrun was introduced in https://gerrit-review.git.corp.google.com/c/gerrit/+/82911 and
  // has never been used. Consider removing it. Since it was never used  and this file has been
  // through many refactorings since, it's likely that the implementation is broken.
  @CanIgnoreReturnValue
  public Change merge(
      Change change,
      IdentifiedUser caller,
      boolean checkSubmitRules,
      SubmitInput submitInput,
      boolean dryrun)
      throws RestApiException,
          UpdateException,
          IOException,
          ConfigInvalidException,
          PermissionBackendException {
    this.submitInput = submitInput;
    this.notify =
        notifyResolver.resolve(
            firstNonNull(submitInput.notify, NotifyHandling.ALL), submitInput.notifyDetails);
    this.dryrun = dryrun;
    this.caller = caller;
    this.ts = TimeUtil.now();
    this.submissionId = new SubmissionId(change);

    try (TraceContext traceContext =
        TraceContext.open()
            .addTag(RequestId.Type.SUBMISSION_ID, new RequestId(submissionId.toString()))) {
      openRepoManager();

      logger.atFine().log("Beginning integration of %s", change);
      try {

        ChangeSet indexBackedChangeSet = completeMergeChangeSetWithRetry(change);

        if (indexBackedChangeSet.furtherHiddenChanges()) {
          throw new AuthException(
              "A change to be submitted with " + change.getId() + " is not visible");
        }
        logger.atFine().log("Calculated to merge %s", indexBackedChangeSet);

        // Reload ChangeSet so that we don't rely on (potentially) stale index data for merging
        ChangeSet noteDbChangeSet = reloadChanges(indexBackedChangeSet);

        // At this point, any change that isn't new can be filtered out since they were only here
        // in the first place due to stale index.
        List<ChangeData> filteredChanges = new ArrayList<>();
        for (ChangeData changeData : noteDbChangeSet.changes()) {
          if (!changeData.change().getStatus().equals(Status.NEW)) {
            logger.atFine().log(
                "Change %s has status %s due to stale index, so it is skipped during submit",
                changeData.getId(), changeData.change().getStatus().name());
            continue;
          }
          filteredChanges.add(changeData);
        }

        // There are no hidden changes (or else we would have thrown AuthException above).
        ChangeSet filteredNoteDbChangeSet =
            new ChangeSet(filteredChanges, /* hiddenChanges= */ ImmutableList.of());

        // Count cross-project submissions outside of the retry loop. The chance of a single project
        // failing increases with the number of projects, so the failure count would be inflated if
        // this metric were incremented inside of integrateIntoHistory.
        int projects = filteredNoteDbChangeSet.projects().size();
        if (projects > 1) {
          topicMetrics.topicSubmissions.increment();
        }

        SubmissionExecutor submissionExecutor =
            new SubmissionExecutor(batchUpdates, dryrun, superprojectUpdateSubmissionListeners);
        RetryTracker retryTracker = new RetryTracker();
        @SuppressWarnings("unused")
        var unused =
            retryHelper
                .changeUpdate(
                    "integrateIntoHistory",
                    updateFactory -> {
                      long attempt = retryTracker.lastAttemptNumber + 1;
                      boolean isRetry = attempt > 1;
                      if (isRetry) {
                        logger.atFine().log(
                            "Retrying, attempt #%d; skipping merged changes", attempt);
                        this.ts = TimeUtil.now();
                        openRepoManager();
                      }
                      this.commitStatus = new CommitStatus(filteredNoteDbChangeSet, isRetry);
                      if (checkSubmitRules) {
                        logger.atFine().log("Checking submit rules and state");
                        checkSubmitRulesAndState(change, filteredNoteDbChangeSet, isRetry);
                      } else {
                        logger.atFine().log("Bypassing submit rules");
                        bypassSubmitRulesAndRequirements(filteredNoteDbChangeSet);
                      }
                      integrateIntoHistory(
                          filteredNoteDbChangeSet, submissionExecutor, checkSubmitRules);
                      return null;
                    })
                .listener(retryTracker)
                // Up to the entire submit operation is retried, including possibly many projects.
                // Multiply the timeout by the number of projects we're actually attempting to
                // submit. Times 2 to retry more persistently, to increase success rate.
                .defaultTimeoutMultiplier(filteredNoteDbChangeSet.projects().size() * 2)
                .call();
        submissionExecutor.afterExecutions(orm);

        if (projects > 1) {
          topicMetrics.topicSubmissionsCompleted.increment();
        }

        // It's expected that callers invoke this method only for open changes and that the provided
        // change either gets updated to merged or that this method fails with an exception. For
        // safety, fall-back to return the provided change if there was no update for this change
        // (e.g. caller provided a change that was already merged).
        return updatedChanges.containsKey(change.getId())
            ? updatedChanges.get(change.getId())
            : change;
      } catch (IOException e) {
        // Anything before the merge attempt is an error
        throw new StorageException(e);
      }
    }
  }

  private ChangeSet completeMergeChangeSetWithRetry(Change change)
      throws IOException, ResourceConflictException {
    try {
      mergeSuperSet.setMergeOpRepoManager(orm);
      // Index can be stale for O(seconds), so attempting to merge the change right after it is
      // updated can result in an exception.
      // Reattempt evaluating the change set with the standard INDEX_QUERY retry timeout
      ChangeSet resultSet =
          retryHelper
              .action(
                  INDEX_QUERY,
                  "completeMergeChangeSet",
                  () -> {
                    Change reloadChange = change;
                    ChangeSet indexBackedMergeChangeSet =
                        mergeSuperSet.completeChangeSet(
                            reloadChange, caller.getRealUser(), /* includingTopicClosure= */ false);
                    if (!indexBackedMergeChangeSet.ids().contains(reloadChange.getId())) {
                      // indexBackedChangeSet contains only open changes, if the change is missing
                      // in this set it might be that the change was concurrently submitted in the
                      // meantime.
                      reloadChange = changeDataFactory.create(reloadChange).reloadChange();
                      if (!reloadChange.isNew()) {
                        throw new ResourceConflictException(
                            "change is " + ChangeUtil.status(reloadChange));
                      }
                      throw new IOException(
                          String.format(
                              "change %s missing from %s",
                              reloadChange.getId(), indexBackedMergeChangeSet));
                    }
                    return indexBackedMergeChangeSet;
                  })
              .retryOn(IOException.class::isInstance)
              .call();
      return resultSet;
    } catch (ResourceConflictException | IOException e) {
      throw e;
    } catch (Exception e) {
      throw new IOException("Computing mergeSuperset has failed", e);
    }
  }

  private void openRepoManager() {
    if (orm != null) {
      orm.close();
    }
    orm = ormProvider.get();
    orm.setContext(ts, caller, notify);
  }

  private ChangeSet reloadChanges(ChangeSet changeSet) {
    List<ChangeData> visible = new ArrayList<>(changeSet.changes().size());
    List<ChangeData> nonVisible = new ArrayList<>(changeSet.nonVisibleChanges().size());
    changeSet.changes().forEach(c -> visible.add(changeDataFactory.create(c.project(), c.getId())));
    changeSet
        .nonVisibleChanges()
        .forEach(c -> nonVisible.add(changeDataFactory.create(c.project(), c.getId())));
    return new ChangeSet(visible, nonVisible);
  }

  private class RetryTracker implements RetryListener {
    long lastAttemptNumber;

    @Override
    public <V> void onRetry(Attempt<V> attempt) {
      lastAttemptNumber = attempt.getAttemptNumber();
    }
  }

  @Singleton
  private static class TopicMetrics {
    final Counter0 topicSubmissions;
    final Counter0 topicSubmissionsCompleted;

    @Inject
    TopicMetrics(MetricMaker metrics) {
      topicSubmissions =
          metrics.newCounter(
              "topic/cross_project_submit",
              new Description("Attempts at cross project topic submission").setRate());
      topicSubmissionsCompleted =
          metrics.newCounter(
              "topic/cross_project_submit_completed",
              new Description("Cross project topic submissions that concluded successfully")
                  .setRate());
    }
  }

  private void integrateIntoHistory(
      ChangeSet cs, SubmissionExecutor submissionExecutor, boolean checkSubmitRules)
      throws RestApiException, UpdateException {
    try (RefUpdateContext ctx = RefUpdateContext.open(MERGE_CHANGE)) {
      checkArgument(!cs.furtherHiddenChanges(), "cannot integrate hidden changes into history");
      logger.atFine().log("Beginning merge attempt on %s", cs);
      Map<BranchNameKey, BranchBatch> toSubmit = new HashMap<>();

      ListMultimap<BranchNameKey, ChangeData> cbb;
      try {
        cbb = cs.changesByBranch();
      } catch (StorageException e) {
        throw new StorageException("Error reading changes to submit", e);
      }
      Set<BranchNameKey> branches = cbb.keySet();

      for (BranchNameKey branch : branches) {
        OpenRepo or = openRepo(branch.project());
        if (or != null) {
          toSubmit.put(branch, validateChangeList(or, cbb.get(branch)));
        }
      }

      // Done checks that don't involve running submit strategies.
      commitStatus.maybeFailVerbose();

      try {
        SubscriptionGraph subscriptionGraph = subscriptionGraphFactory.compute(branches, orm);
        SubmoduleCommits submoduleCommits = submoduleCommitsFactory.create(orm);
        UpdateOrderCalculator updateOrderCalculator = new UpdateOrderCalculator(subscriptionGraph);
        List<SubmitStrategy> strategies =
            getSubmitStrategies(
                toSubmit, updateOrderCalculator, submoduleCommits, subscriptionGraph, dryrun);
        this.projects = updateOrderCalculator.getProjectsInOrder();
        List<BatchUpdate> batchUpdates =
            orm.batchUpdates(
                projects, /* refLogMessage= */ checkSubmitRules ? "merged" : "forced-merge");
        // Group batch updates by project
        Map<Project.NameKey, BatchUpdate> batchUpdatesByProject =
            batchUpdates.stream()
                .collect(Collectors.toMap(b -> b.getProject(), Function.identity()));
        for (Map.Entry<Change.Id, ChangeData> entry : cs.changesById().entrySet()) {
          Project.NameKey project = entry.getValue().project();
          Change.Id changeId = entry.getKey();
          ChangeData cd = entry.getValue();
          batchUpdatesByProject
              .get(project)
              .addOp(
                  changeId,
                  storeSubmitRequirementsOpFactory.create(
                      cd.submitRequirementsIncludingLegacy().values(), cd));
        }
        try {
          submissionExecutor.setAdditionalBatchUpdateListeners(
              ImmutableList.of(new SubmitStrategyListener(submitInput, strategies, commitStatus)));
          submissionExecutor.execute(batchUpdates);
        } finally {
          // If the BatchUpdate fails it can be that merging some of the changes was actually
          // successful. This is why we must to collect the updated changes also when an
          // exception was thrown.
          strategies.forEach(s -> updatedChanges.putAll(s.getUpdatedChanges()));

          // Do not leave executed BatchUpdates in the OpenRepos
          if (!dryrun) {
            orm.resetUpdates(ImmutableSet.copyOf(this.projects));
          }
        }
      } catch (NoSuchProjectException e) {
        throw new ResourceNotFoundException(e.getMessage());
      } catch (IOException e) {
        throw new StorageException(e);
      } catch (SubmoduleConflictException e) {
        throw new IntegrationConflictException(e.getMessage(), e);
      } catch (UpdateException e) {
        if (e.getCause() instanceof LockFailureException) {
          // Lock failures are a special case: RetryHelper depends on this specific causal chain in
          // order to trigger a retry. The downside of throwing here is we will not get the nicer
          // error message constructed below, in the case where this is the final attempt and the
          // operation is not retried further. This is not a huge downside, and is hopefully so rare
          // as to be unnoticeable, assuming RetryHelper is retrying sufficiently.
          throw e;
        }

        // BatchUpdate may have inadvertently wrapped an IntegrationConflictException
        // thrown by some legacy SubmitStrategyOp code that intended the error
        // message to be user-visible. Copy the message from the wrapped
        // exception.
        //
        // If you happen across one of these, the correct fix is to convert the
        // inner IntegrationConflictException to a ResourceConflictException.
        if (e.getCause() instanceof IntegrationConflictException) {
          throw (IntegrationConflictException) e.getCause();
        }
        throw new MergeUpdateException(genericMergeError(cs), e);
      }
    }
  }

  public Set<Project.NameKey> getAllProjects() {
    return projects;
  }

  public MergeOpRepoManager getMergeOpRepoManager() {
    return orm;
  }

  private List<SubmitStrategy> getSubmitStrategies(
      Map<BranchNameKey, BranchBatch> toSubmit,
      UpdateOrderCalculator updateOrderCalculator,
      SubmoduleCommits submoduleCommits,
      SubscriptionGraph subscriptionGraph,
      boolean dryrun)
      throws IntegrationConflictException, NoSuchProjectException, IOException {
    List<SubmitStrategy> strategies = new ArrayList<>();
    ImmutableSet<BranchNameKey> allBranches = updateOrderCalculator.getBranchesInOrder();
    Set<CodeReviewCommit> allCommits =
        toSubmit.values().stream().map(BranchBatch::commits).flatMap(Set::stream).collect(toSet());

    for (BranchNameKey branch : allBranches) {
      OpenRepo or = orm.getRepo(branch.project());
      if (toSubmit.containsKey(branch)) {
        BranchBatch submitting = toSubmit.get(branch);
        logger.atFine().log("adding ops for branch %s, batch = %s", branch, submitting);
        OpenBranch ob = or.getBranch(branch);
        requireNonNull(
            submitting.submitType(),
            String.format("null submit type for %s; expected to previously fail fast", submitting));
        ImmutableSet<CodeReviewCommit> commitsToSubmit = submitting.commits();
        checkImplicitMerges(
            branch, or.rw, submitting.commits(), submitting.submitType(), ob.oldTip);

        ob.mergeTip = new MergeTip(ob.oldTip, commitsToSubmit);
        SubmitStrategy strategy =
            submitStrategyFactory.create(
                submitting.submitType(),
                or.rw,
                or.canMergeFlag,
                getAlreadyAccepted(or, ob.oldTip),
                allCommits,
                branch,
                caller,
                ob.mergeTip,
                commitStatus,
                submissionId,
                submitInput,
                submoduleCommits,
                subscriptionGraph,
                dryrun);
        strategies.add(strategy);
        strategy.addOps(or.getUpdate(), commitsToSubmit);
      }
    }

    return strategies;
  }

  private void checkImplicitMerges(
      BranchNameKey branch,
      RevWalk rw,
      Set<CodeReviewCommit> commitsToSubmit,
      SubmitType submitType,
      @Nullable RevCommit branchTip)
      throws IOException {
    if (branchTip == null) {
      // The branch doesn't exist.
      return;
    }
    Project.NameKey project = branch.project();
    if (!experimentFeatures.isFeatureEnabled(
        GERRIT_BACKEND_FEATURE_CHECK_IMPLICIT_MERGES_ON_MERGE, project)) {
      return;
    }
    if (submitType == SubmitType.CHERRY_PICK || submitType == SubmitType.REBASE_ALWAYS) {
      return;
    }

    boolean projectConfigRejectImplicitMerges =
        projectCache
            .get(project)
            .orElseThrow(illegalState(project))
            .is(BooleanProjectConfig.REJECT_IMPLICIT_MERGES);
    boolean rejectImplicitMergesOnMerges =
        experimentFeatures.isFeatureEnabled(
                GERRIT_BACKEND_FEATURE_REJECT_IMPLICIT_MERGES_ON_MERGE, project)
            && (experimentFeatures.isFeatureEnabled(
                    GERRIT_BACKEND_FEATURE_ALWAYS_REJECT_IMPLICIT_MERGES_ON_MERGE, project)
                || projectConfigRejectImplicitMerges);
    try {
      if (hasImplicitMerges(branch, rw, commitsToSubmit, branchTip)) {
        if (rejectImplicitMergesOnMerges) {
          commitStatus.addImplicitMerge(project, branch);
        } else {
          String allCommits =
              commitsToSubmit.stream()
                  .map(CodeReviewCommit::getId)
                  .map(c -> ObjectId.toString(c))
                  .collect(joining(", "));
          logger.atWarning().log(
              "Implicit merge was detected for the branch %s of the project %s. "
                  + "Commits to be merged are: %s",
              branch.shortName(), project, allCommits);
        }
      }
    } catch (Exception e) {
      if (rejectImplicitMergesOnMerges) {
        throw e;
      }
      logger.atWarning().withCause(e).log("Error while checking for implicit merges");
    }
  }

  private boolean isMergedInBranchAsSubmittedChange(RevCommit commit, BranchNameKey dest) {
    List<ChangeData> changes = queryProvider.get().byBranchCommit(dest, commit.getId().getName());
    for (ChangeData change : changes) {
      if (change.change().isMerged()) {
        logger.atFine().log(
            "Dependency %s associated with merged change %s.", commit.getName(), change.getId());
        return true;
      }
    }
    return false;
  }

  /**
   * Checks if merging {@code commitsToSubmit} into the target branch leads to implicit merge.
   *
   * <p>All commits in the {@code commitsToSubmit} have {@code targetBranch} as a target. When
   * multiple changes are submitted together, the {@code commitsToSubmit} contains transitive
   * dependencies, not a single change (the method is never called for the cherry pick strategy
   * because the strategy always submit a single change).
   */
  private boolean hasImplicitMerges(
      BranchNameKey targetBranch,
      RevWalk rw,
      Set<CodeReviewCommit> commitsToSubmit,
      RevCommit branchTip)
      throws IOException {

    // rootCommits - top level commits in chains. It is all commits which don't have children in
    // the commitsToSubmit set (no commits have them as parents).
    Set<CodeReviewCommit> rootCommits = new HashSet<>(commitsToSubmit);
    Set<RevCommit> allParents = new HashSet<>();
    for (CodeReviewCommit commit : commitsToSubmit) {
      rw.parseBody(commit);
      for (RevCommit parent : commit.getParents()) {
        rootCommits.remove(parent);
        allParents.add(parent);
      }
    }

    // Calculate all "external" parents of commitsToSubmit - i.e. all parents which already
    // present in the repository.
    // targetBranchParents - all "external" parents which were merged into the targetBranch (
    // they are reachable from the targetBranchTip).
    Set<RevCommit> targetBranchParents = new HashSet<>();
    int nonTargetBranchParentsCount = 0;
    try {
      for (RevCommit parent : Sets.difference(allParents, commitsToSubmit)) {
        if (rw.isMergedInto(parent, branchTip)) {
          targetBranchParents.add(parent);
        } else {
          // Special case: user created chain of changes and then submit first changes from the
          // chain. It should be allowed for the user to submit remaining changes of the chain
          // without rebasing them (otherwise votes can be lost).
          // When a rebase... strategy is used in this scenario, submitting the first few changes of
          // the chain creates new patchset(s), but all others changes are not rebased on top of new
          // patchset(s). In this situation isMergedInto check is not enough and additional
          // isMergedInBranchAsSubmittedChange check should be used.
          if (isMergedInBranchAsSubmittedChange(parent, targetBranch)) {
            targetBranchParents.add(parent);
          } else {
            nonTargetBranchParentsCount++;
          }
        }
      }
    } finally {
      // It's unclear why resetting the RevWalk here is needed, but if we don't do this MergeSorter
      // and RebaseSorter which are invoked later with the same RevWalk instance may fail while
      // marking commits as uninteresting.
      rw.reset();
    }

    if (nonTargetBranchParentsCount == 0) {
      // All parents are in target branch, no implicit merge is possible.
      return false;
    }
    // There are some parents not in the target branch.
    if (rootCommits.size() == 1) {
      // There is only one root commit - this is the case when a single chain of changes is
      // submitted to the branch.
      // If the target branch is not reachable from the root commit then it means that there is no
      // explicit merge with the target branch and the merge operation will create an implicit merge
      // (except if rebase is used; but for consistency between different strategies we reject
      // merge even for rebase).
      return targetBranchParents.isEmpty();
    }
    // There are multiple root commits - check that a target branch is reachable from each root
    // commit. This situation means that multiple chain of changes are submitted (e.g. as a part
    // of a single topic).
    // reachableCommits contains pairs of commit: the first item in pair is always one of the root
    // commits. The second item in pair - a commit reachable from this root (following parents).
    // Loop implements breadth-search.
    Deque<Entry<CodeReviewCommit, RevCommit>> reachableCommits =
        new ArrayDeque<>(rootCommits.size());
    rootCommits.forEach(commit -> reachableCommits.add(Map.entry(commit, commit)));
    // Tracks all chains roots which can lead to implicit merge.
    Set<CodeReviewCommit> implicitMergesRoots = new HashSet<>(rootCommits);
    int iterationCount = 0;
    Stopwatch sw = Stopwatch.createStarted();
    while (!reachableCommits.isEmpty()) {
      iterationCount++;
      if (hasImplicitMergeTimeoutSeconds != 0
          && sw.elapsed(TimeUnit.SECONDS) >= hasImplicitMergeTimeoutSeconds) {
        String allCommits =
            commitsToSubmit.stream()
                .map(CodeReviewCommit::getId)
                .map(c -> ObjectId.toString(c))
                .collect(joining(", "));
        logger.atWarning().log(
            "Timeout during hasImplicitMerge calculation. Number of iterations: %s,"
                + " commitsToSubmit: %s",
            iterationCount, allCommits);
        return true;
      }
      Entry<CodeReviewCommit, RevCommit> entry = reachableCommits.pop();
      if (!implicitMergesRoots.contains(entry.getKey())) {
        // We already know that from the given root (key in the entry) one of the
        // targetBranchParents is reachable and this is not an implicit merge.
        continue;
      }
      if (targetBranchParents.contains(entry.getValue())) {
        // The target branch is reachable from the root. We don't need to process other items
        // in the queue for this root.
        implicitMergesRoots.remove(entry.getKey());
        continue;
      }
      if (entry.getValue() == null) {
        logger.atSevere().log("The entry value is null for the key %s", entry.getKey());
      }
      rw.parseBody(entry.getValue());
      if (entry.getValue().getParents() == null) {
        logger.atSevere().log(
            "The entry value has null parents. The value is: %s", entry.getValue());
      }
      for (RevCommit parent : entry.getValue().getParents()) {
        reachableCommits.push(Map.entry(entry.getKey(), parent));
      }
    }
    // only commits which don't have parents in the targetBranch remains in the implicitMergesRoots.
    // If there are at least one commit - this is an implicit merge.
    return !implicitMergesRoots.isEmpty();
  }

  private Set<RevCommit> getAlreadyAccepted(OpenRepo or, CodeReviewCommit branchTip) {
    Set<RevCommit> alreadyAccepted = new HashSet<>();

    if (branchTip != null) {
      alreadyAccepted.add(branchTip);
    }

    try {
      for (Ref r : or.repo.getRefDatabase().getRefsByPrefix(Constants.R_HEADS)) {
        try {
          CodeReviewCommit aac = or.rw.parseCommit(r.getObjectId());
          if (!commitStatus.commits.values().contains(aac)) {
            alreadyAccepted.add(aac);
          }
        } catch (IncorrectObjectTypeException iote) {
          // Not a commit? Skip over it.
        }
      }
    } catch (IOException e) {
      throw new StorageException("Failed to determine already accepted commits.", e);
    }

    logger.atFine().log("Found %d existing heads: %s", alreadyAccepted.size(), alreadyAccepted);
    return alreadyAccepted;
  }

  @AutoValue
  abstract static class BranchBatch {
    @Nullable
    abstract SubmitType submitType();

    abstract ImmutableSet<CodeReviewCommit> commits();
  }

  private BranchBatch validateChangeList(OpenRepo or, Collection<ChangeData> submitted) {
    logger.atFine().log("Validating %d changes", submitted.size());
    Set<CodeReviewCommit> toSubmit = new LinkedHashSet<>(submitted.size());
    SetMultimap<ObjectId, PatchSet.Id> revisions = getRevisions(or, submitted);

    SubmitType submitType = null;
    ChangeData choseSubmitTypeFrom = null;
    for (ChangeData cd : submitted) {
      Change.Id changeId = cd.getId();
      ChangeNotes notes;
      Change chg;
      SubmitType st;
      try {
        notes = cd.notes();
        chg = cd.change();
        st = getSubmitType(cd);
      } catch (StorageException e) {
        commitStatus.logProblem(changeId, e);
        continue;
      }

      if (st == null) {
        commitStatus.logProblem(changeId, "No submit type for change");
        continue;
      }
      if (submitType == null) {
        submitType = st;
        choseSubmitTypeFrom = cd;
      } else if (st != submitType) {
        commitStatus.problem(
            changeId,
            String.format(
                "Change has submit type %s, but previously chose submit type %s "
                    + "from change %s in the same batch",
                st, submitType, choseSubmitTypeFrom.getId()));
        continue;
      }
      if (chg.currentPatchSetId() == null) {
        String msg = "Missing current patch set on change";
        logger.atSevere().log("%s %s", msg, changeId);
        commitStatus.problem(changeId, msg);
        continue;
      }

      PatchSet ps;
      BranchNameKey destBranch = chg.getDest();
      try {
        ps = cd.currentPatchSet();
      } catch (StorageException e) {
        commitStatus.logProblem(changeId, e);
        continue;
      }
      if (ps == null) {
        commitStatus.logProblem(changeId, "Missing patch set on change");
        continue;
      }

      ObjectId id = ps.commitId();
      if (!revisions.containsEntry(id, ps.id())) {
        if (revisions.containsValue(ps.id())) {
          // TODO This is actually an error, the patch set ref exists but points to a revision that
          // is different from the revision that we have stored for the patch set in the change
          // meta data.
          commitStatus.logProblem(
              changeId,
              "Revision "
                  + id.name()
                  + " of patch set "
                  + ps.number()
                  + " does not match the revision of the patch set ref "
                  + ps.id().toRefName());
          continue;
        }

        // The patch set ref is not found but we want to merge the change. We can't safely do that
        // if the patch set ref is missing. In a cluster setups with multiple primary nodes this can
        // indicate a replication lag (e.g. the change meta data was already replicated, but the
        // replication of the patch set ref is still pending).
        commitStatus.logProblem(
            changeId,
            "Patch set ref "
                + ps.id().toRefName()
                + " not found. Expected patch set ref of "
                + ps.number()
                + " to point to revision "
                + id.name());
        continue;
      }

      CodeReviewCommit commit;
      try {
        commit = or.rw.parseCommit(id);
      } catch (IOException e) {
        commitStatus.logProblem(changeId, e);
        continue;
      }

      commit.setNotes(notes);
      commit.setPatchsetId(ps.id());
      commitStatus.put(commit);

      MergeValidators mergeValidators = mergeValidatorsFactory.create();
      try {
        mergeValidators.validatePreMerge(
            or.repo, or.rw, commit, or.project, destBranch, ps.id(), caller);
      } catch (MergeValidationException mve) {
        commitStatus.problem(changeId, mve.getMessage());
        continue;
      }
      commit.add(or.canMergeFlag);
      toSubmit.add(commit);
    }
    logger.atFine().log("Submitting on this run: %s", toSubmit);
    return new AutoValue_MergeOp_BranchBatch(submitType, ImmutableSet.copyOf(toSubmit));
  }

  private SetMultimap<ObjectId, PatchSet.Id> getRevisions(OpenRepo or, Collection<ChangeData> cds) {
    try {
      List<String> refNames = new ArrayList<>(cds.size());
      for (ChangeData cd : cds) {
        Change c = cd.change();
        if (c != null) {
          refNames.add(c.currentPatchSetId().toRefName());
        }
      }
      SetMultimap<ObjectId, PatchSet.Id> revisions =
          MultimapBuilder.hashKeys(cds.size()).hashSetValues(1).build();
      for (Map.Entry<String, Ref> e :
          or.repo
              .getRefDatabase()
              .exactRef(refNames.toArray(new String[refNames.size()]))
              .entrySet()) {
        revisions.put(e.getValue().getObjectId(), PatchSet.Id.fromRef(e.getKey()));
      }
      return revisions;
    } catch (IOException | StorageException e) {
      throw new StorageException("Failed to validate changes", e);
    }
  }

  @Nullable
  private SubmitType getSubmitType(ChangeData cd) {
    SubmitTypeRecord str = cd.submitTypeRecord();
    return str.isOk() ? str.type : null;
  }

  @Nullable
  private OpenRepo openRepo(Project.NameKey project) {
    try {
      return orm.getRepo(project);
    } catch (NoSuchProjectException e) {
      logger.atWarning().log("Project %s no longer exists, abandoning open changes.", project);
      abandonAllOpenChangeForDeletedProject(project);
    } catch (IOException e) {
      throw new StorageException("Error opening project " + project, e);
    }
    return null;
  }

  private void abandonAllOpenChangeForDeletedProject(Project.NameKey destProject) {
    try {
      for (ChangeData cd : queryProvider.get().byProjectOpen(destProject)) {
        try (BatchUpdate bu =
            batchUpdateFactory.create(destProject, internalUserFactory.create(), ts)) {
          bu.addOp(
              cd.getId(),
              new BatchUpdateOp() {
                @Override
                public boolean updateChange(ChangeContext ctx) {
                  Change change = ctx.getChange();
                  if (!change.isNew()) {
                    return false;
                  }

                  change.setStatus(Change.Status.ABANDONED);

                  cmUtil.setChangeMessage(
                      ctx, "Project was deleted.", ChangeMessagesUtil.TAG_MERGED);

                  return true;
                }
              });
          try {
            bu.execute();
          } catch (UpdateException | RestApiException e) {
            logger.atWarning().withCause(e).log(
                "Cannot abandon changes for deleted project %s", destProject);
          }
        }
      }
    } catch (StorageException e) {
      logger.atWarning().withCause(e).log(
          "Cannot abandon changes for deleted project %s", destProject);
    }
  }

  private String genericMergeError(ChangeSet cs) {
    int c = cs.size();
    if (c == 1) {
      return "Error submitting change";
    }
    int p = cs.projects().size();
    if (p == 1) {
      // Fused updates: it's correct to say that none of the n changes were submitted.
      return "Error submitting " + c + " changes";
    }
    // Multiple projects involved, but we don't know at this point what failed. At least give the
    // user a heads up that some changes may be unsubmitted, even if the change screen they land on
    // after the error message says that this particular change was submitted.
    return "Error submitting some of the "
        + c
        + " changes to one or more of the "
        + p
        + " projects involved; some projects may have submitted successfully, but others may have"
        + " failed";
  }
}
