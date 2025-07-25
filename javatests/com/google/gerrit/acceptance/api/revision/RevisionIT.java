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

package com.google.gerrit.acceptance.api.revision;

import static com.google.common.truth.Truth.assertThat;
import static com.google.gerrit.acceptance.PushOneCommit.FILE_CONTENT;
import static com.google.gerrit.acceptance.PushOneCommit.FILE_NAME;
import static com.google.gerrit.acceptance.PushOneCommit.PATCH;
import static com.google.gerrit.acceptance.PushOneCommit.PATCH_FILE_ONLY;
import static com.google.gerrit.acceptance.PushOneCommit.SUBJECT;
import static com.google.gerrit.acceptance.TestExtensions.TestCommitValidationInfoListener;
import static com.google.gerrit.acceptance.TestExtensions.TestCommitValidationListener;
import static com.google.gerrit.acceptance.TestExtensions.TestValidationOptionsListener;
import static com.google.gerrit.acceptance.testsuite.project.TestProjectUpdate.allow;
import static com.google.gerrit.entities.Patch.COMMIT_MSG;
import static com.google.gerrit.entities.Patch.MERGE_LIST;
import static com.google.gerrit.entities.Patch.PATCHSET_LEVEL;
import static com.google.gerrit.extensions.client.ListChangesOption.ALL_REVISIONS;
import static com.google.gerrit.extensions.client.ListChangesOption.CURRENT_COMMIT;
import static com.google.gerrit.extensions.client.ListChangesOption.CURRENT_REVISION;
import static com.google.gerrit.extensions.client.ListChangesOption.DETAILED_LABELS;
import static com.google.gerrit.server.group.SystemGroupBackend.REGISTERED_USERS;
import static com.google.gerrit.testing.GerritJUnit.assertThrows;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.toList;
import static org.eclipse.jgit.lib.Constants.HEAD;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import com.google.common.collect.ListMultimap;
import com.google.gerrit.acceptance.AbstractDaemonTest;
import com.google.gerrit.acceptance.ExtensionRegistry;
import com.google.gerrit.acceptance.ExtensionRegistry.Registration;
import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.RestResponse;
import com.google.gerrit.acceptance.TestAccount;
import com.google.gerrit.acceptance.TestProjectInput;
import com.google.gerrit.acceptance.UseClockStep;
import com.google.gerrit.acceptance.config.GerritConfig;
import com.google.gerrit.acceptance.testsuite.account.AccountOperations;
import com.google.gerrit.acceptance.testsuite.change.ChangeOperations;
import com.google.gerrit.acceptance.testsuite.project.ProjectOperations;
import com.google.gerrit.acceptance.testsuite.request.RequestScopeOperations;
import com.google.gerrit.entities.Account;
import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.entities.BranchOrderSection;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.LabelId;
import com.google.gerrit.entities.PatchSet;
import com.google.gerrit.entities.PatchSetApproval;
import com.google.gerrit.entities.Permission;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.extensions.api.changes.ChangeApi;
import com.google.gerrit.extensions.api.changes.CherryPickInput;
import com.google.gerrit.extensions.api.changes.DraftApi;
import com.google.gerrit.extensions.api.changes.DraftInput;
import com.google.gerrit.extensions.api.changes.NotifyHandling;
import com.google.gerrit.extensions.api.changes.NotifyInfo;
import com.google.gerrit.extensions.api.changes.RecipientType;
import com.google.gerrit.extensions.api.changes.ReviewInput;
import com.google.gerrit.extensions.api.changes.ReviewInput.CommentInput;
import com.google.gerrit.extensions.api.changes.RevisionApi;
import com.google.gerrit.extensions.api.projects.BranchInput;
import com.google.gerrit.extensions.api.projects.ConfigInput;
import com.google.gerrit.extensions.client.ChangeStatus;
import com.google.gerrit.extensions.client.ListChangesOption;
import com.google.gerrit.extensions.client.ReviewerState;
import com.google.gerrit.extensions.client.SubmitType;
import com.google.gerrit.extensions.common.AccountInfo;
import com.google.gerrit.extensions.common.ApprovalInfo;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.extensions.common.ChangeMessageInfo;
import com.google.gerrit.extensions.common.CommentInfo;
import com.google.gerrit.extensions.common.CommitInfo;
import com.google.gerrit.extensions.common.FileInfo;
import com.google.gerrit.extensions.common.GitPerson;
import com.google.gerrit.extensions.common.LabelInfo;
import com.google.gerrit.extensions.common.MergeableInfo;
import com.google.gerrit.extensions.common.RevisionInfo;
import com.google.gerrit.extensions.common.RevisionInfo.ParentInfo;
import com.google.gerrit.extensions.common.WebLinkInfo;
import com.google.gerrit.extensions.events.ChangeIndexedListener;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.BinaryResult;
import com.google.gerrit.extensions.restapi.MethodNotAllowedException;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.extensions.restapi.ResourceNotFoundException;
import com.google.gerrit.extensions.restapi.UnprocessableEntityException;
import com.google.gerrit.extensions.webui.PatchSetWebLink;
import com.google.gerrit.extensions.webui.ResolveConflictsWebLink;
import com.google.gerrit.server.git.validators.CommitValidationInfo;
import com.google.gerrit.server.query.change.ChangeData;
import com.google.gerrit.server.util.AccountTemplateUtil;
import com.google.gerrit.testing.FakeEmailSender;
import com.google.inject.Inject;
import java.io.ByteArrayOutputStream;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.Test;

public class RevisionIT extends AbstractDaemonTest {
  @Inject private ProjectOperations projectOperations;
  @Inject private RequestScopeOperations requestScopeOperations;
  @Inject private ExtensionRegistry extensionRegistry;
  @Inject private AccountOperations accountOperations;
  @Inject private ChangeOperations changeOperations;

  @Test
  public void reviewTriplet() throws Exception {
    PushOneCommit.Result r = createChange();
    gApi.changes()
        .id(project.get() + "~master~" + r.getChangeId())
        .revision(r.getCommit().name())
        .review(ReviewInput.approve());
  }

  @Test
  public void reviewCurrent() throws Exception {
    PushOneCommit.Result r = createChange();
    gApi.changes().id(r.getChangeId()).current().review(ReviewInput.approve());
  }

  @Test
  public void reviewNumber() throws Exception {
    PushOneCommit.Result r = createChange();
    gApi.changes().id(r.getChangeId()).revision(1).review(ReviewInput.approve());

    r = updateChange(r, "new content");
    gApi.changes().id(r.getChangeId()).revision(2).review(ReviewInput.approve());
  }

  @Test
  public void submit() throws Exception {
    PushOneCommit.Result r = createChange();
    String changeId = project.get() + "~master~" + r.getChangeId();
    gApi.changes().id(changeId).current().review(ReviewInput.approve());
    ChangeInfo changeInfo = gApi.changes().id(changeId).current().submit();
    assertThat(changeInfo.status).isEqualTo(ChangeStatus.MERGED);
    assertThat(gApi.changes().id(changeId).get().status).isEqualTo(ChangeStatus.MERGED);
  }

  @Test
  public void postSubmitApproval() throws Exception {
    PushOneCommit.Result r = createChange();
    String changeId = project.get() + "~master~" + r.getChangeId();
    gApi.changes().id(changeId).current().review(ReviewInput.recommend());

    String label = LabelId.CODE_REVIEW;
    ApprovalInfo approval = getApproval(changeId, label);
    assertThat(approval.value).isEqualTo(1);
    assertThat(approval.postSubmit).isNull();

    // Submit by direct push.
    git().push().setRefSpecs(new RefSpec(r.getCommit().name() + ":refs/heads/master")).call();
    assertThat(gApi.changes().id(changeId).get().status).isEqualTo(ChangeStatus.MERGED);

    approval = getApproval(changeId, label);
    assertThat(approval.value).isEqualTo(1);
    assertThat(approval.postSubmit).isNull();
    assertPermitted(gApi.changes().id(changeId).get(DETAILED_LABELS), LabelId.CODE_REVIEW, 1, 2);

    // Repeating the current label is allowed. Does not flip the postSubmit bit due to
    // deduplication codepath.
    gApi.changes().id(changeId).current().review(ReviewInput.recommend());
    approval = getApproval(changeId, label);
    assertThat(approval.value).isEqualTo(1);
    assertThat(approval.postSubmit).isNull();

    // Reducing vote is not allowed.
    ResourceConflictException thrown =
        assertThrows(
            ResourceConflictException.class,
            () -> gApi.changes().id(changeId).current().review(ReviewInput.dislike()));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Cannot reduce vote on labels for closed change: Code-Review");
    approval = getApproval(changeId, label);
    assertThat(approval.value).isEqualTo(1);
    assertThat(approval.postSubmit).isNull();

    // Increasing vote is allowed.
    gApi.changes().id(changeId).current().review(ReviewInput.approve());
    approval = getApproval(changeId, label);
    assertThat(approval.value).isEqualTo(2);
    assertThat(approval.postSubmit).isTrue();
    assertPermitted(gApi.changes().id(changeId).get(DETAILED_LABELS), LabelId.CODE_REVIEW, 2);

    // Decreasing to previous post-submit vote is still not allowed.
    thrown =
        assertThrows(
            ResourceConflictException.class,
            () -> gApi.changes().id(changeId).current().review(ReviewInput.dislike()));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Cannot reduce vote on labels for closed change: Code-Review");
    approval = getApproval(changeId, label);
    assertThat(approval.value).isEqualTo(2);
    assertThat(approval.postSubmit).isTrue();
  }

  @Test
  public void postSubmitApprovalAfterVoteRemoved() throws Exception {
    PushOneCommit.Result r = createChange();
    String changeId = project.get() + "~master~" + r.getChangeId();

    requestScopeOperations.setApiUser(admin.id());
    revision(r).review(ReviewInput.approve());

    requestScopeOperations.setApiUser(user.id());
    revision(r).review(ReviewInput.recommend());

    requestScopeOperations.setApiUser(admin.id());
    gApi.changes().id(changeId).reviewer(user.username()).deleteVote(LabelId.CODE_REVIEW);
    Optional<ApprovalInfo> crUser =
        get(changeId, DETAILED_LABELS).labels.get(LabelId.CODE_REVIEW).all.stream()
            .filter(a -> a._accountId == user.id().get())
            .findFirst();
    assertThat(crUser).isPresent();
    assertThat(crUser.get().value).isEqualTo(0);

    revision(r).submit();

    requestScopeOperations.setApiUser(user.id());
    ReviewInput in = new ReviewInput();
    in.label(LabelId.CODE_REVIEW, 1);
    in.message = "Still LGTM";
    revision(r).review(in);

    ApprovalInfo cr =
        gApi
            .changes()
            .id(changeId)
            .get(DETAILED_LABELS)
            .labels
            .get(LabelId.CODE_REVIEW)
            .all
            .stream()
            .filter(a -> a._accountId == user.id().get())
            .findFirst()
            .get();
    assertThat(cr.postSubmit).isTrue();
  }

  @Test
  public void postSubmitDeleteApprovalNotAllowed() throws Exception {
    PushOneCommit.Result r = createChange();

    revision(r).review(ReviewInput.approve());
    revision(r).submit();

    ReviewInput in = new ReviewInput();
    in.label(LabelId.CODE_REVIEW, 0);

    ResourceConflictException thrown =
        assertThrows(ResourceConflictException.class, () -> revision(r).review(in));
    assertThat(thrown)
        .hasMessageThat()
        .contains("Cannot reduce vote on labels for closed change: Code-Review");
  }

  @TestProjectInput(submitType = SubmitType.CHERRY_PICK)
  @Test
  public void approvalCopiedDuringSubmitIsNotPostSubmit() throws Exception {
    PushOneCommit.Result r = createChange();
    Change.Id id = r.getChange().getId();
    gApi.changes().id(id.get()).current().review(ReviewInput.approve());
    gApi.changes().id(id.get()).current().submit();

    ChangeData cd = r.getChange();
    assertThat(cd.patchSets()).hasSize(2);
    PatchSetApproval psa =
        Iterators.getOnlyElement(
            cd.currentApprovals().stream().filter(a -> !a.isLegacySubmit()).iterator());
    assertThat(psa.patchSetId().get()).isEqualTo(2);
    assertThat(psa.label()).isEqualTo(LabelId.CODE_REVIEW);
    assertThat(psa.value()).isEqualTo(2);
    assertThat(psa.postSubmit()).isFalse();
  }

  @Test
  public void voteOnAbandonedChange() throws Exception {
    PushOneCommit.Result r = createChange();
    gApi.changes().id(r.getChangeId()).abandon();
    ResourceConflictException thrown =
        assertThrows(
            ResourceConflictException.class,
            () -> gApi.changes().id(r.getChangeId()).current().review(ReviewInput.reject()));
    assertThat(thrown).hasMessageThat().contains("change is closed");
  }

  @Test
  public void voteNotAllowedWithoutPermission() throws Exception {
    PushOneCommit.Result r = createChange();
    requestScopeOperations.setApiUser(user.id());
    AuthException thrown =
        assertThrows(AuthException.class, () -> change(r).current().review(ReviewInput.approve()));
    assertThat(thrown).hasMessageThat().contains("is restricted");
  }

  @Test
  public void cherryPick() throws Exception {
    RevCommit initialHead = projectOperations.project(project).getHead("master");

    PushOneCommit.Result r = pushTo("refs/for/master%topic=someTopic");
    CherryPickInput in = new CherryPickInput();
    in.destination = "foo";
    in.message = "it goes to stable branch";
    gApi.projects().name(project.get()).branch(in.destination).create(new BranchInput());
    ChangeApi orig = gApi.changes().id(project.get() + "~master~" + r.getChangeId());

    assertThat(orig.get().messages).hasSize(1);
    ChangeInfo changeInfo = orig.revision(r.getCommit().name()).cherryPickAsInfo(in);
    assertThat(changeInfo.containsGitConflicts).isNull();
    assertThat(changeInfo.workInProgress).isNull();
    ChangeApi cherry = gApi.changes().id(changeInfo._number);

    // Verify the conflicts information
    RevCommit head = projectOperations.project(project).getHead(changeInfo.branch);
    RevisionInfo currentRevision =
        gApi.changes().id(changeInfo.id).get(CURRENT_REVISION, CURRENT_COMMIT).getCurrentRevision();
    assertThat(currentRevision.commit.parents.get(0).commit).isEqualTo(head.name());
    assertThat(currentRevision.conflicts).isNotNull();
    assertThat(currentRevision.conflicts.containsConflicts).isFalse();
    assertThat(currentRevision.conflicts.base).isEqualTo(initialHead.getName());
    assertThat(currentRevision.conflicts.ours).isEqualTo(head.getName());
    assertThat(currentRevision.conflicts.theirs).isEqualTo(r.getCommit().name());
    assertThat(currentRevision.conflicts.mergeStrategy).isEqualTo("recursive");
    assertThat(currentRevision.conflicts.noBaseReason).isNull();

    ChangeInfo cherryPickChangeInfoWithDetails = cherry.get();
    assertThat(cherryPickChangeInfoWithDetails.workInProgress).isNull();
    assertThat(cherryPickChangeInfoWithDetails.messages).hasSize(1);
    Iterator<ChangeMessageInfo> cherryIt = cherryPickChangeInfoWithDetails.messages.iterator();
    assertThat(cherryIt.next().message).isEqualTo("Patch Set 1: Cherry Picked from branch master.");

    assertThat(cherry.get().subject).contains(in.message);
    assertThat(cherry.get().topic).isEqualTo("someTopic-foo");
    assertThat(cherry.get().cherryPickOfChange).isEqualTo(orig.get()._number);
    assertThat(cherry.get().cherryPickOfPatchSet).isEqualTo(1);

    cherry.current().review(ReviewInput.approve());
    cherry.current().submit();
  }

  @Test
  public void cherryPickAfterUpdatingPreferredEmail() throws Exception {
    String emailOne = "email1@example.com";
    Account.Id testUser = accountOperations.newAccount().preferredEmail(emailOne).create();

    // Create target branch to cherry-pick to
    String branch = "foo";
    gApi.projects().name(project.get()).branch(branch).create(new BranchInput());

    // Create change to cherry-pick
    Change.Id changeId =
        changeOperations
            .newChange()
            .project(project)
            .file("file")
            .content("content")
            .owner(testUser)
            .createV1();

    // Change preferred email for the user
    String emailTwo = "email2@example.com";
    accountOperations.account(testUser).forUpdate().preferredEmail(emailTwo).update();
    requestScopeOperations.setApiUser(testUser);

    // Cherry-pick the change
    String commit = gApi.changes().id(changeId.get()).get().getCurrentRevision().commit.commit;
    CherryPickInput input = new CherryPickInput();
    input.destination = branch;
    input.message = "cherry-pick to foo branch";
    ChangeInfo changeInfo =
        gApi.changes().id(changeId.get()).revision(commit).cherryPick(input).get();
    assertThat(changeInfo.getCurrentRevision().commit.committer.email).isEqualTo(emailOne);
  }

  @Test
  public void cherryPickWithoutMessage() throws Exception {
    String branch = "foo";

    // Create change to cherry-pick
    PushOneCommit.Result change = createChange();
    RevCommit revCommit = change.getCommit();

    // Create target branch to cherry-pick to.
    gApi.projects().name(project.get()).branch(branch).create(new BranchInput());

    // Cherry-pick without message.
    CherryPickInput input = new CherryPickInput();
    input.destination = branch;
    String changeId =
        gApi.changes()
            .id(change.getChangeId())
            .revision(revCommit.name())
            .cherryPickAsInfo(input)
            .id;

    // Expect that the message of the cherry-picked commit was used for the cherry-pick change.
    ChangeInfo changeInfo = gApi.changes().id(changeId).get();
    RevisionInfo revInfo = changeInfo.revisions.get(changeInfo.currentRevision);
    assertThat(revInfo).isNotNull();
    assertThat(revInfo.commit.message).isEqualTo(revCommit.getFullMessage());
  }

  @Test
  public void cherryPickSetChangeId() throws Exception {
    PushOneCommit.Result r = pushTo("refs/for/master");
    CherryPickInput in = new CherryPickInput();
    in.destination = "foo";
    String id = "Ideadbeefdeadbeefdeadbeefdeadbeefdeadbe3f";
    in.message = "it goes to foo branch\n\nChange-Id: " + id;

    gApi.projects().name(project.get()).branch(in.destination).create(new BranchInput());
    ChangeApi orig = gApi.changes().id(project.get() + "~master~" + r.getChangeId());

    assertThat(orig.get().messages).hasSize(1);
    ChangeApi cherry = orig.revision(r.getCommit().name()).cherryPick(in);

    ChangeInfo changeInfo = cherry.get();

    // The cherry-pick honors the ChangeId specified in the input message:
    RevisionInfo revInfo = changeInfo.revisions.get(changeInfo.currentRevision);
    // New change was created.
    assertThat(changeInfo._number).isGreaterThan(orig.get()._number);
    assertThat(changeInfo.changeId).isEqualTo(id);
    assertThat(revInfo).isNotNull();
    assertThat(revInfo.commit.message.trim()).endsWith(id);
  }

  @Test
  public void cherryPickWithUnRegisteredCommitterEmail() throws Exception {
    // Create change to cherry-pick
    PushOneCommit.Result r = pushTo("refs/for/master");

    // Create target branch to cherry-pick to
    String destination = "foo";
    gApi.projects().name(project.get()).branch(destination).create(new BranchInput());

    // Cherry-pick the change
    CherryPickInput in = new CherryPickInput();
    in.destination = destination;
    in.message = "it goes to foo branch";
    in.committerEmail = "secondary@example.org";
    BadRequestException thrown =
        assertThrows(
            BadRequestException.class,
            () -> gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).cherryPick(in));
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            "Cannot cherry-pick using committer email "
                + in.committerEmail
                + ", as it is not among the registered emails of account "
                + admin.id().get());
  }

  @Test
  public void cherryPickWithNonPreferredCommitterEmail() throws Exception {
    // Create change to cherry-pick
    PushOneCommit.Result r = pushTo("refs/for/master");

    // Create target branch to cherry-pick to
    String destination = "foo";
    gApi.projects().name(project.get()).branch(destination).create(new BranchInput());

    // Create a user with secondary email
    Account.Id userWithSecondaryEmail =
        accountOperations
            .newAccount()
            .preferredEmail("preferred@example.org")
            .addSecondaryEmail("secondary@example.org")
            .create();
    requestScopeOperations.setApiUser(userWithSecondaryEmail);

    // Cherry-pick the change
    CherryPickInput in = new CherryPickInput();
    in.destination = destination;
    in.message = "it goes to foo branch";
    in.committerEmail = "secondary@example.org";
    ChangeApi cherry =
        gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).cherryPick(in);
    assertThat(cherry.get().getCurrentRevision().commit.committer.email)
        .isEqualTo(in.committerEmail);
  }

  @Test
  public void cherryPickWithNoTopic() throws Exception {
    PushOneCommit.Result r = pushTo("refs/for/master");
    CherryPickInput in = new CherryPickInput();
    in.destination = "foo";
    in.message = "it goes to stable branch";
    gApi.projects().name(project.get()).branch(in.destination).create(new BranchInput());
    ChangeApi orig = gApi.changes().id(project.get() + "~master~" + r.getChangeId());

    ChangeApi cherry = orig.revision(r.getCommit().name()).cherryPick(in);
    assertThat(cherry.get().topic).isNull();
    cherry.current().review(ReviewInput.approve());
    cherry.current().submit();
  }

  @Test
  public void cherryPickWithSetTopic() throws Exception {
    PushOneCommit.Result r = pushTo("refs/for/master");
    CherryPickInput in = new CherryPickInput();
    in.destination = "foo";
    in.topic = "topic";
    gApi.projects().name(project.get()).branch(in.destination).create(new BranchInput());
    ChangeApi orig = gApi.changes().id(project.get() + "~master~" + r.getChangeId());

    ChangeApi cherry = orig.revision(r.getCommit().name()).cherryPick(in);
    assertThat(cherry.get().topic).isEqualTo("topic");
  }

  @Test
  public void cherryPickNewPatchsetWithSetTopic() throws Exception {
    PushOneCommit.Result r = pushTo("refs/for/master");
    CherryPickInput in = new CherryPickInput();
    in.destination = "foo";
    gApi.projects().name(project.get()).branch(in.destination).create(new BranchInput());
    ChangeApi orig = gApi.changes().id(project.get() + "~master~" + r.getChangeId());

    ChangeApi cherry = orig.revision(r.getCommit().name()).cherryPick(in);
    assertThat(cherry.get().topic).isNull();
    in.topic = "topic";
    cherry = orig.revision(r.getCommit().name()).cherryPick(in);
    assertThat(cherry.get().topic).isEqualTo("topic");
  }

  @Test
  public void cherryPickNewPatchsetWithNoTopic() throws Exception {
    PushOneCommit.Result r = pushTo("refs/for/master");
    CherryPickInput in = new CherryPickInput();
    in.destination = "foo";
    in.topic = "topic";
    gApi.projects().name(project.get()).branch(in.destination).create(new BranchInput());
    ChangeApi orig = gApi.changes().id(project.get() + "~master~" + r.getChangeId());

    ChangeApi cherry = orig.revision(r.getCommit().name()).cherryPick(in);
    assertThat(cherry.get().topic).isEqualTo("topic");

    in.topic = null;
    cherry = orig.revision(r.getCommit().name()).cherryPick(in);
    // confirm that the topic doesn't change when not specified.
    assertThat(cherry.get().topic).isEqualTo("topic");
  }

  @Test
  public void cherryPickWorkInProgressChange() throws Exception {
    PushOneCommit.Result r = pushTo("refs/for/master%wip");
    CherryPickInput in = new CherryPickInput();
    in.destination = "foo";
    in.message = "cherry pick message";
    gApi.projects().name(project.get()).branch(in.destination).create(new BranchInput());
    ChangeApi orig = gApi.changes().id(project.get() + "~master~" + r.getChangeId());

    ChangeApi cherry = orig.revision(r.getCommit().name()).cherryPick(in);
    assertThat(cherry.get().cherryPickOfChange).isEqualTo(orig.get()._number);
    assertThat(cherry.get().cherryPickOfPatchSet).isEqualTo(1);
    assertThat(cherry.get().workInProgress).isTrue();
  }

  @Test
  public void cherryPickToSameBranch() throws Exception {
    PushOneCommit.Result r = createChange();
    ChangeApi change = gApi.changes().id(project.get() + "~master~" + r.getChangeId());
    CherryPickInput in = new CherryPickInput();
    in.destination = "master";
    ChangeInfo cherryInfo = change.current().cherryPick(in).get();
    assertThat(cherryInfo.messages).hasSize(2);
    Iterator<ChangeMessageInfo> cherryMessageIt = cherryInfo.messages.iterator();
    assertThat(cherryInfo.cherryPickOfChange).isEqualTo(change.get()._number);

    // Existing change was updated.
    assertThat(cherryInfo._number).isEqualTo(change.get()._number);
    assertThat(cherryInfo.cherryPickOfChange).isEqualTo(change.get()._number);
    assertThat(cherryInfo.cherryPickOfPatchSet).isEqualTo(1);
    assertThat(cherryMessageIt.next().message).isEqualTo("Uploaded patch set 1.");
    assertThat(cherryMessageIt.next().message)
        .isEqualTo("Patch Set 2: Cherry Picked from branch master.");
  }

  @Test
  public void restoreOldPatchSetByCherryPickToSameBranch() throws Exception {
    Change.Id changeId =
        changeOperations.newChange().project(project).file("a.txt").content("aContent").createV1();
    ChangeApi change = gApi.changes().id(project.get(), changeId.get());

    // Amend the change, deleting file a.txt and adding file b.txt.
    changeOperations
        .change(changeId)
        .newPatchset()
        .file("a.txt")
        .delete()
        .file("b.txt")
        .content("bContent")
        .create();

    // Restore patch set 1 by cherry-picking it.
    CherryPickInput in = new CherryPickInput();
    in.destination = "master";
    ChangeInfo cherryInfo = change.revision(1).cherryPick(in).get();
    assertThat(cherryInfo.messages).hasSize(3);
    Iterator<ChangeMessageInfo> cherryMessageIt = cherryInfo.messages.iterator();
    assertThat(cherryInfo.cherryPickOfChange).isEqualTo(change.get()._number);

    // Existing change was updated.
    assertThat(cherryInfo._number).isEqualTo(change.get()._number);
    assertThat(cherryInfo.cherryPickOfChange).isEqualTo(change.get()._number);
    assertThat(cherryInfo.cherryPickOfPatchSet).isEqualTo(1);
    assertThat(cherryMessageIt.next().message).isEqualTo("Uploaded patch set 1.");
    assertThat(cherryMessageIt.next().message).isEqualTo("Uploaded patch set 2.");
    assertThat(cherryMessageIt.next().message)
        .isEqualTo("Patch Set 3: Cherry Picked from branch master.");

    // File a.txt has been restored and b.txt has been removed.
    Map<String, FileInfo> files = change.current().files();
    assertThat(files.keySet()).containsExactly("a.txt", COMMIT_MSG);
  }

  @Test
  public void cherryPickToSameBranchWithRebase() throws Exception {
    // Push a new change, then merge it
    PushOneCommit.Result baseChange = createChange();
    String triplet = project.get() + "~master~" + baseChange.getChangeId();
    RevisionApi baseRevision = gApi.changes().id(triplet).current();
    baseRevision.review(ReviewInput.approve());
    baseRevision.submit();

    // Push a new change (change 1)
    PushOneCommit.Result r1 = createChange();

    // Push another new change (change 2)
    String subject = "Test change";
    PushOneCommit push =
        pushFactory.create(
            admin.newIdent(), testRepo, subject, "another_file.txt", "another content");
    PushOneCommit.Result r2 = push.to("refs/for/master");

    // Change 2's parent should be change 1
    assertThat(r2.getCommit().getParents()[0].name()).isEqualTo(r1.getCommit().name());

    // Cherry pick change 2 onto the same branch
    triplet = project.get() + "~master~" + r2.getChangeId();
    ChangeApi orig = gApi.changes().id(triplet);
    CherryPickInput in = new CherryPickInput();
    in.destination = "master";
    in.message = subject + "\n\nChange-Id: " + r2.getChangeId();
    ChangeApi cherry = orig.revision(r2.getCommit().name()).cherryPick(in);
    ChangeInfo cherryInfo = cherry.get();
    assertThat(cherryInfo.messages).hasSize(2);
    Iterator<ChangeMessageInfo> cherryIt = cherryInfo.messages.iterator();
    assertThat(cherryIt.next().message).isEqualTo("Uploaded patch set 1.");
    assertThat(cherryIt.next().message).isEqualTo("Patch Set 2: Cherry Picked from branch master.");

    // Parent of change 2 should now be the change that was merged, i.e.
    // change 2 is rebased onto the head of the master branch.
    String newParent =
        cherryInfo.revisions.get(cherryInfo.currentRevision).commit.parents.get(0).commit;
    assertThat(newParent).isEqualTo(baseChange.getCommit().name());
  }

  @Test
  public void cherryPickIdenticalTree() throws Exception {
    PushOneCommit.Result r = createChange();
    CherryPickInput in = new CherryPickInput();
    in.destination = "foo";
    in.message = "it goes to stable branch";
    gApi.projects().name(project.get()).branch(in.destination).create(new BranchInput());
    ChangeApi orig = gApi.changes().id(project.get() + "~master~" + r.getChangeId());

    assertThat(orig.get().messages).hasSize(1);
    ChangeApi cherry = orig.revision(r.getCommit().name()).cherryPick(in);

    assertThat(cherry.get().subject).contains(in.message);
    cherry.current().review(ReviewInput.approve());
    cherry.current().submit();

    ResourceConflictException thrown =
        assertThrows(
            ResourceConflictException.class,
            () -> orig.revision(r.getCommit().name()).cherryPick(in));
    assertThat(thrown).hasMessageThat().contains("Cherry pick failed: identical tree");

    in.allowEmpty = true;
    ChangeInfo cherryPickChange = orig.revision(r.getCommit().name()).cherryPick(in).get();
    assertThat(cherryPickChange.cherryPickOfChange).isEqualTo(r.getChange().change().getChangeId());

    // An empty commit is created
    assertThat(cherryPickChange.insertions).isEqualTo(0);
    assertThat(cherryPickChange.deletions).isEqualTo(0);
  }

  @Test
  public void cherryPickConflict() throws Exception {
    PushOneCommit.Result r = createChange();
    CherryPickInput in = new CherryPickInput();
    in.destination = "foo";
    in.message = "it goes to stable branch";
    gApi.projects().name(project.get()).branch(in.destination).create(new BranchInput());

    PushOneCommit push =
        pushFactory.create(
            admin.newIdent(),
            testRepo,
            PushOneCommit.SUBJECT,
            PushOneCommit.FILE_NAME,
            "another content");
    push.to("refs/heads/foo");

    String triplet = project.get() + "~master~" + r.getChangeId();
    ChangeApi orig = gApi.changes().id(triplet);
    assertThat(orig.get().messages).hasSize(1);

    ResourceConflictException thrown =
        assertThrows(
            ResourceConflictException.class,
            () -> orig.revision(r.getCommit().name()).cherryPick(in));
    assertThat(thrown).hasMessageThat().contains("Cherry pick failed: merge conflict");
  }

  @Test
  public void cherryPickConflictWithAllowConflicts() throws Exception {
    testCherryPickConflictWithAllowConflicts(/* useDiff3= */ false);
  }

  @Test
  @GerritConfig(name = "change.diff3ConflictView", value = "true")
  public void cherryPickConflictWithAllowConflictsUsingDiff3() throws Exception {
    testCherryPickConflictWithAllowConflicts(/* useDiff3= */ true);
  }

  private void testCherryPickConflictWithAllowConflicts(boolean useDiff3) throws Exception {
    RevCommit initialHead = projectOperations.project(project).getHead("master");

    // Create a branch and push a commit to it (by-passing review)
    String destBranch = "foo";
    gApi.projects().name(project.get()).branch(destBranch).create(new BranchInput());
    String destContent = "some content";
    PushOneCommit push =
        pushFactory.create(
            admin.newIdent(),
            testRepo,
            PushOneCommit.SUBJECT,
            ImmutableMap.of(PushOneCommit.FILE_NAME, destContent, "foo.txt", "foo"));
    push.to("refs/heads/" + destBranch);

    // Create a change on master with a commit that conflicts with the commit on the other branch.
    testRepo.reset(initialHead);
    String changeContent = "another content";
    push =
        pushFactory.create(
            admin.newIdent(),
            testRepo,
            PushOneCommit.SUBJECT,
            ImmutableMap.of(PushOneCommit.FILE_NAME, changeContent, "bar.txt", "bar"));
    PushOneCommit.Result r = push.to("refs/for/master%topic=someTopic");

    // Verify before the cherry-pick that the change has exactly 1 message.
    ChangeApi changeApi = change(r);
    assertThat(changeApi.get().messages).hasSize(1);

    // Cherry-pick the change to the other branch, that should fail with a conflict.
    CherryPickInput in = new CherryPickInput();
    in.destination = destBranch;
    in.message = "Cherry-Pick";
    ResourceConflictException thrown =
        assertThrows(
            ResourceConflictException.class,
            () -> changeApi.revision(r.getCommit().name()).cherryPickAsInfo(in));
    assertThat(thrown).hasMessageThat().startsWith("Cherry pick failed: merge conflict");

    // Cherry-pick with auto merge should succeed.
    in.allowConflicts = true;
    ChangeInfo cherryPickChange = changeApi.revision(r.getCommit().name()).cherryPickAsInfo(in);
    assertThat(cherryPickChange.containsGitConflicts).isTrue();
    assertThat(cherryPickChange.workInProgress).isTrue();

    // Verify the conflicts information
    RevCommit head = projectOperations.project(project).getHead(cherryPickChange.branch);
    RevisionInfo currentRevision =
        gApi.changes()
            .id(cherryPickChange.id)
            .get(CURRENT_REVISION, CURRENT_COMMIT)
            .getCurrentRevision();
    assertThat(currentRevision.commit.parents.get(0).commit).isEqualTo(head.name());
    assertThat(currentRevision.conflicts).isNotNull();
    assertThat(currentRevision.conflicts.containsConflicts).isTrue();
    assertThat(currentRevision.conflicts.base).isEqualTo(initialHead.getName());
    assertThat(currentRevision.conflicts.ours).isEqualTo(head.getName());
    assertThat(currentRevision.conflicts.theirs).isEqualTo(r.getCommit().name());
    assertThat(currentRevision.conflicts.mergeStrategy).isEqualTo("recursive");
    assertThat(currentRevision.conflicts.noBaseReason).isNull();

    // Verify that subject and topic on the cherry-pick change have been correctly populated.
    assertThat(cherryPickChange.subject).contains(in.message);
    assertThat(cherryPickChange.topic).isEqualTo("someTopic-" + destBranch);

    // Verify that the file content in the cherry-pick change is correct.
    // We expect that it has conflict markers to indicate the conflict.
    BinaryResult bin =
        gApi.changes()
            .id(cherryPickChange._number)
            .current()
            .file(PushOneCommit.FILE_NAME)
            .content();
    ByteArrayOutputStream os = new ByteArrayOutputStream();
    bin.writeTo(os);
    String fileContent = new String(os.toByteArray(), UTF_8);
    assertThat(fileContent)
        .isEqualTo(
            "<<<<<<< HEAD   ("
                + projectOperations.project(project).getHead(destBranch).getName()
                + " test commit)\n"
                + destContent
                + "\n"
                + (useDiff3
                    ? String.format(
                        "||||||| BASE   (%s %s)\n",
                        initialHead.getName(), initialHead.getShortMessage())
                    : "")
                + "=======\n"
                + changeContent
                + "\n"
                + ">>>>>>> CHANGE ("
                + r.getCommit().getName()
                + " test commit)\n");

    // Get details of cherry-pick change.
    ChangeInfo cherryPickChangeWithDetails = gApi.changes().id(cherryPickChange._number).get();
    assertThat(cherryPickChangeWithDetails.workInProgress).isTrue();

    // Verify that a message has been posted on the cherry-pick change.
    assertThat(cherryPickChangeWithDetails.messages).hasSize(1);
    Iterator<ChangeMessageInfo> cherryIt = cherryPickChangeWithDetails.messages.iterator();
    assertThat(cherryIt.next().message)
        .isEqualTo(
            "Patch Set 1: Cherry Picked from branch master.\n\n"
                + "The following files contain Git conflicts:\n"
                + "* "
                + PushOneCommit.FILE_NAME);
  }

  @Test
  public void cherryPickToExistingChangeWithAllowConflictsSetsWIPOnConflict() throws Exception {
    String tip = testRepo.getRepository().exactRef("HEAD").getObjectId().name();

    String destBranch = "foo";
    createBranch(BranchNameKey.create(project, destBranch));
    PushOneCommit.Result existingChange =
        createChange(testRepo, destBranch, SUBJECT, FILE_NAME, "some content", null);

    testRepo.reset(tip);
    PushOneCommit.Result srcChange =
        createChange(testRepo, "master", SUBJECT, FILE_NAME, "other content", null);

    CherryPickInput input = new CherryPickInput();
    input.destination = destBranch;
    input.base = existingChange.getCommit().name();
    input.message = "cherry-pick to foo" + "\n\nChange-Id: " + existingChange.getChangeId();
    input.allowConflicts = true;
    ChangeInfo changeInfo =
        change(srcChange).revision(srcChange.getCommit().name()).cherryPickAsInfo(input);

    assertThat(changeInfo.containsGitConflicts).isTrue();
    assertThat(changeInfo.workInProgress).isTrue();

    // Verify the conflicts information
    RevisionInfo currentRevision =
        gApi.changes().id(changeInfo.id).get(CURRENT_REVISION, CURRENT_COMMIT).getCurrentRevision();
    assertThat(currentRevision.commit.parents.get(0).commit)
        .isEqualTo(existingChange.getCommit().name());
    assertThat(currentRevision.conflicts).isNotNull();
    assertThat(currentRevision.conflicts.containsConflicts).isTrue();
    assertThat(currentRevision.conflicts.base).isEqualTo(tip);
    assertThat(currentRevision.conflicts.ours).isEqualTo(existingChange.getCommit().name());
    assertThat(currentRevision.conflicts.theirs).isEqualTo(srcChange.getCommit().name());
    assertThat(currentRevision.conflicts.mergeStrategy).isEqualTo("recursive");
    assertThat(currentRevision.conflicts.noBaseReason).isNull();
  }

  @Test
  public void cherryPickWithValidationOptions() throws Exception {
    PushOneCommit.Result r = createChange();

    CherryPickInput in = new CherryPickInput();
    in.destination = "foo";
    in.message = "Cherry Pick";
    in.validationOptions = ImmutableMap.of("key", "value");

    gApi.projects().name(project.get()).branch(in.destination).create(new BranchInput());

    TestCommitValidationListener testCommitValidationListener = new TestCommitValidationListener();
    TestValidationOptionsListener testValidationOptionsListener =
        new TestValidationOptionsListener();
    try (Registration registration =
        extensionRegistry
            .newRegistration()
            .add(testCommitValidationListener)
            .add(testValidationOptionsListener)) {
      gApi.changes().id(r.getChangeId()).current().cherryPick(in);
      assertThat(testCommitValidationListener.receiveEvent.pushOptions)
          .containsExactly("key", "value");
      assertThat(testValidationOptionsListener.validationOptions).containsExactly("key", "value");
    }
  }

  @Test
  public void cherryPickToExistingChangeUpdatesCherryPickOf() throws Exception {
    PushOneCommit.Result r1 =
        pushFactory
            .create(admin.newIdent(), testRepo, SUBJECT, FILE_NAME, "a")
            .to("refs/for/master");
    String t1 = project.get() + "~master~" + r1.getChangeId();
    ChangeApi orig = gApi.changes().id(project.get() + "~master~" + r1.getChangeId());

    BranchInput bin = new BranchInput();
    bin.revision = r1.getCommit().getParent(0).name();
    gApi.projects().name(project.get()).branch("foo").create(bin);

    PushOneCommit.Result r2 =
        pushFactory
            .create(admin.newIdent(), testRepo, SUBJECT, FILE_NAME, "b", r1.getChangeId())
            .to("refs/for/foo");
    String t2 = project.get() + "~foo~" + r2.getChangeId();

    CherryPickInput in = new CherryPickInput();
    in.destination = "foo";
    in.message = r1.getCommit().getFullMessage();
    ChangeApi cherry = gApi.changes().id(t1).current().cherryPick(in);
    assertThat(get(t2, ALL_REVISIONS).revisions).hasSize(2);
    assertThat(cherry.get().cherryPickOfChange).isEqualTo(orig.get()._number);
    assertThat(cherry.get().cherryPickOfPatchSet).isEqualTo(1);

    PushOneCommit.Result r3 = amendChange(r1.getChangeId(), SUBJECT, "b.txt", "b");
    in = new CherryPickInput();
    in.destination = "foo";
    in.message = r3.getCommit().getFullMessage();
    cherry = gApi.changes().id(t1).current().cherryPick(in);
    assertThat(cherry.get()._number).isEqualTo(info(t2)._number);
    assertThat(cherry.get().cherryPickOfChange).isEqualTo(orig.get()._number);
    assertThat(cherry.get().cherryPickOfPatchSet).isEqualTo(2);
  }

  @Test
  public void commitValidationInfoListenerIsInvokedOnPatchSetCreation() throws Exception {
    // Do a cherry-pick to an existing change to invoke PatchSetInserter to create a new patch set.

    // Create a change on the master branch that we can cherry-pick
    PushOneCommit.Result r =
        pushFactory
            .create(admin.newIdent(), testRepo, SUBJECT, FILE_NAME, "a")
            .to("refs/for/master");
    Change.Id origChangeId = r.getChange().getId();

    // Create a branch foo to which we can cherry-pick.
    BranchInput branchInput = new BranchInput();
    branchInput.revision = r.getCommit().getParent(0).name();
    gApi.projects().name(project.get()).branch("foo").create(branchInput);

    // Create a change on branch foo that we will update by cherry pick.
    pushFactory
        .create(admin.newIdent(), testRepo, SUBJECT, FILE_NAME, "b", r.getChangeId())
        .to("refs/for/foo")
        .assertOkStatus();

    // Cherry-pick the change from the master branch to the foo branch so that it updates the
    // existing change on the foo branch.
    TestCommitValidationInfoListener testCommitValidationInfoListener =
        new TestCommitValidationInfoListener();
    TestCommitValidationListener testCommitValidationListener = new TestCommitValidationListener();
    try (Registration registration =
        extensionRegistry
            .newRegistration()
            .add(testCommitValidationInfoListener)
            .add(testCommitValidationListener)) {
      CherryPickInput in = new CherryPickInput();
      in.destination = "foo";
      in.message = r.getCommit().getFullMessage();
      in.validationOptions = ImmutableMap.of("key", "value");
      ChangeApi cherry =
          gApi.changes().id(project.get(), origChangeId.get()).current().cherryPick(in);
      ChangeInfo changeInfo = cherry.get();
      assertThat(changeInfo.currentRevisionNumber).isEqualTo(2);
      assertThat(changeInfo.cherryPickOfChange).isEqualTo(origChangeId.get());
      assertThat(changeInfo.cherryPickOfPatchSet).isEqualTo(1);

      assertThat(testCommitValidationInfoListener.validationInfoByValidator)
          .containsKey(TestCommitValidationListener.class.getName());
      assertThat(
              testCommitValidationInfoListener
                  .validationInfoByValidator
                  .get(TestCommitValidationListener.class.getName())
                  .status())
          .isEqualTo(CommitValidationInfo.Status.PASSED);
      assertThat(testCommitValidationInfoListener.receiveEvent.commit.name())
          .isEqualTo(changeInfo.currentRevision);
      assertThat(testCommitValidationInfoListener.receiveEvent.pushOptions)
          .containsExactly("key", "value");
      assertThat(testCommitValidationInfoListener.patchSetId)
          .isEqualTo(PatchSet.id(Change.id(changeInfo._number), changeInfo.currentRevisionNumber));
      assertThat(testCommitValidationInfoListener.hasChangeModificationRefContext).isTrue();
      assertThat(testCommitValidationInfoListener.hasDirectPushRefContext).isFalse();
    }
  }

  @Test
  public void cherryPickNonCurrentPatchSetHasCorrectCherryPickOf() throws Exception {
    PushOneCommit.Result r =
        pushFactory
            .create(admin.newIdent(), testRepo, SUBJECT, FILE_NAME, "a")
            .to("refs/for/master");
    String t1 = project.get() + "~master~" + r.getChangeId();
    ChangeApi orig = gApi.changes().id(project.get() + "~master~" + r.getChangeId());

    BranchInput bin = new BranchInput();
    bin.revision = r.getCommit().getParent(0).name();
    gApi.projects().name(project.get()).branch("foo").create(bin);

    // Create a second patchset before cherry-picking
    String newMessage = "modified commit" + "\n\nChange-Id: " + r.getChangeId() + "\n";
    gApi.changes().id(r.getChangeId()).setMessage(newMessage);

    CherryPickInput in = new CherryPickInput();
    in.destination = "foo";
    in.message = r.getCommit().getFullMessage();
    ChangeApi cherry = gApi.changes().id(t1).revision(r.getCommit().name()).cherryPick(in);
    assertThat(cherry.get().cherryPickOfChange).isEqualTo(orig.get()._number);
    assertThat(cherry.get().cherryPickOfPatchSet).isEqualTo(1);
  }

  @Test
  public void cherryPickToAbandonedChange() throws Exception {
    PushOneCommit.Result r1 =
        pushFactory
            .create(admin.newIdent(), testRepo, SUBJECT, FILE_NAME, "a")
            .to("refs/for/master");
    String t1 = project.get() + "~master~" + r1.getChangeId();

    BranchInput bin = new BranchInput();
    bin.revision = r1.getCommit().getParent(0).name();
    gApi.projects().name(project.get()).branch("foo").create(bin);

    PushOneCommit.Result r2 =
        pushFactory
            .create(admin.newIdent(), testRepo, SUBJECT, FILE_NAME, "b", r1.getChangeId())
            .to("refs/for/foo");
    String t2 = project.get() + "~foo~" + r2.getChangeId();
    gApi.changes().id(t2).abandon();

    CherryPickInput in = new CherryPickInput();
    in.destination = "foo";
    in.message = r1.getCommit().getFullMessage();
    BadRequestException thrown =
        assertThrows(
            BadRequestException.class, () -> gApi.changes().id(t1).current().cherryPick(in));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(
            String.format(
                "Cherry-pick with Change-Id %s could not update the existing change %d in "
                    + "destination branch refs/heads/foo of project %s, because "
                    + "the change was closed (ABANDONED)",
                r1.getChangeId(), info(t2)._number, project.get()));

    gApi.changes().id(t2).restore();
    gApi.changes().id(t1).current().cherryPick(in);
    assertThat(get(t2, ALL_REVISIONS).revisions).hasSize(2);
    assertThat(gApi.changes().id(t2).current().file(FILE_NAME).content().asString()).isEqualTo("a");
  }

  @Test
  public void cherryPickToExistingMergedChange() throws Exception {
    PushOneCommit.Result r1 =
        pushFactory
            .create(admin.newIdent(), testRepo, SUBJECT, FILE_NAME, "a")
            .to("refs/for/master");

    BranchInput bin = new BranchInput();
    bin.revision = r1.getCommit().getParent(0).name();
    gApi.projects().name(project.get()).branch("foo").create(bin);

    PushOneCommit.Result r2 =
        pushFactory
            .create(admin.newIdent(), testRepo, SUBJECT, FILE_NAME, "b", r1.getChangeId())
            .to("refs/for/foo");
    String t2 = project.get() + "~foo~" + r2.getChangeId();

    gApi.changes().id(t2).current().review(ReviewInput.approve());
    gApi.changes().id(t2).current().submit();

    CherryPickInput in = new CherryPickInput();
    in.destination = "foo";
    in.message = r1.getCommit().getFullMessage();
    in.allowConflicts = true;
    in.allowEmpty = true;
    BadRequestException thrown =
        assertThrows(
            BadRequestException.class, () -> gApi.changes().id(t2).current().cherryPick(in));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(
            String.format(
                "Cherry-pick with Change-Id %s could not update the existing change %d in "
                    + "destination branch refs/heads/foo of project %s, because "
                    + "the change was closed (MERGED)",
                r1.getChangeId(), info(t2)._number, project.get()));
  }

  @Test
  public void cherryPickMergeRelativeToDefaultParent() throws Exception {
    String parent1FileName = "a.txt";
    String parent2FileName = "b.txt";
    PushOneCommit.Result mergeChangeResult =
        createCherryPickableMerge(parent1FileName, parent2FileName);

    String cherryPickBranchName = "branch_for_cherry_pick";
    createBranch(BranchNameKey.create(project, cherryPickBranchName));

    CherryPickInput cherryPickInput = new CherryPickInput();
    cherryPickInput.destination = cherryPickBranchName;
    cherryPickInput.message = "Cherry-pick a merge commit to another branch";

    ChangeInfo cherryPickedChangeInfo =
        gApi.changes()
            .id(mergeChangeResult.getChangeId())
            .current()
            .cherryPick(cherryPickInput)
            .get();

    Map<String, FileInfo> cherryPickedFilesByName =
        cherryPickedChangeInfo.revisions.get(cherryPickedChangeInfo.currentRevision).files;
    assertThat(cherryPickedFilesByName).containsKey(parent2FileName);
    assertThat(cherryPickedFilesByName).doesNotContainKey(parent1FileName);
  }

  @Test
  public void cherryPickMergeRelativeToSpecificParent() throws Exception {
    String parent1FileName = "a.txt";
    String parent2FileName = "b.txt";
    PushOneCommit.Result mergeChangeResult =
        createCherryPickableMerge(parent1FileName, parent2FileName);

    String cherryPickBranchName = "branch_for_cherry_pick";
    createBranch(BranchNameKey.create(project, cherryPickBranchName));

    CherryPickInput cherryPickInput = new CherryPickInput();
    cherryPickInput.destination = cherryPickBranchName;
    cherryPickInput.message = "Cherry-pick a merge commit to another branch";
    cherryPickInput.parent = 2;

    ChangeInfo cherryPickedChangeInfo =
        gApi.changes()
            .id(mergeChangeResult.getChangeId())
            .current()
            .cherryPick(cherryPickInput)
            .get();

    Map<String, FileInfo> cherryPickedFilesByName =
        cherryPickedChangeInfo.revisions.get(cherryPickedChangeInfo.currentRevision).files;
    assertThat(cherryPickedFilesByName).containsKey(parent1FileName);
    assertThat(cherryPickedFilesByName).doesNotContainKey(parent2FileName);
  }

  @Test
  public void cherryPickMergeUsingInvalidParent() throws Exception {
    String parent1FileName = "a.txt";
    String parent2FileName = "b.txt";
    PushOneCommit.Result mergeChangeResult =
        createCherryPickableMerge(parent1FileName, parent2FileName);

    String cherryPickBranchName = "branch_for_cherry_pick";
    createBranch(BranchNameKey.create(project, cherryPickBranchName));

    CherryPickInput cherryPickInput = new CherryPickInput();
    cherryPickInput.destination = cherryPickBranchName;
    cherryPickInput.message = "Cherry-pick a merge commit to another branch";
    cherryPickInput.parent = 0;

    BadRequestException thrown =
        assertThrows(
            BadRequestException.class,
            () ->
                gApi.changes()
                    .id(mergeChangeResult.getChangeId())
                    .current()
                    .cherryPick(cherryPickInput));
    assertThat(thrown)
        .hasMessageThat()
        .contains("Cherry Pick: Parent 0 does not exist. Please specify a parent in range [1, 2].");
  }

  @Test
  public void cherryPickMergeUsingNonExistentParent() throws Exception {
    String parent1FileName = "a.txt";
    String parent2FileName = "b.txt";
    PushOneCommit.Result mergeChangeResult =
        createCherryPickableMerge(parent1FileName, parent2FileName);

    String cherryPickBranchName = "branch_for_cherry_pick";
    createBranch(BranchNameKey.create(project, cherryPickBranchName));

    CherryPickInput cherryPickInput = new CherryPickInput();
    cherryPickInput.destination = cherryPickBranchName;
    cherryPickInput.message = "Cherry-pick a merge commit to another branch";
    cherryPickInput.parent = 3;

    BadRequestException thrown =
        assertThrows(
            BadRequestException.class,
            () ->
                gApi.changes()
                    .id(mergeChangeResult.getChangeId())
                    .current()
                    .cherryPick(cherryPickInput));
    assertThat(thrown)
        .hasMessageThat()
        .contains("Cherry Pick: Parent 3 does not exist. Please specify a parent in range [1, 2].");
  }

  @Test
  public void cherryPickNotify() throws Exception {
    createBranch(BranchNameKey.create(project, "branch-1"));
    createBranch(BranchNameKey.create(project, "branch-2"));
    createBranch(BranchNameKey.create(project, "branch-3"));

    // Creates a change for 'admin'.
    PushOneCommit.Result result = createChange();
    String changeId = project.get() + "~master~" + result.getChangeId();

    // 'user' cherry-picks the change to a new branch, the source change's author/committer('admin')
    // will be added as cc of the newly created change.
    requestScopeOperations.setApiUser(user.id());
    CherryPickInput input = new CherryPickInput();
    input.message = "it goes to a new branch";

    // Enable the notification. 'admin' as a reviewer should be notified.
    input.destination = "branch-1";
    input.notify = NotifyHandling.ALL;
    sender.clear();
    gApi.changes().id(changeId).current().cherryPick(input);
    assertNotifyCc(admin);

    // Disable the notification. 'admin' as a reviewer should not be notified any more.
    input.destination = "branch-2";
    input.notify = NotifyHandling.NONE;
    sender.clear();
    gApi.changes().id(changeId).current().cherryPick(input);
    assertThat(sender.getMessages()).isEmpty();

    // Disable the notification. The user provided in the 'notifyDetails' should still be notified.
    TestAccount userToNotify = accountCreator.user2();
    input.destination = "branch-3";
    input.notify = NotifyHandling.NONE;
    input.notifyDetails =
        ImmutableMap.of(RecipientType.TO, new NotifyInfo(ImmutableList.of(userToNotify.email())));
    sender.clear();
    gApi.changes().id(changeId).current().cherryPick(input);
    assertNotifyTo(userToNotify);
  }

  @Test
  public void cherryPickKeepReviewers() throws Exception {
    createBranch(BranchNameKey.create(project, "stable"));

    // Change is created by 'admin'.
    PushOneCommit.Result r = createChange();
    // Change is approved by 'admin2'. Change is CC'd to 'user'.
    requestScopeOperations.setApiUser(accountCreator.admin2().id());
    ReviewInput in = ReviewInput.approve();
    in.reviewer(user.email(), ReviewerState.CC, true);
    gApi.changes().id(r.getChangeId()).current().review(in);

    // Change is cherrypicked by 'user2'.
    requestScopeOperations.setApiUser(accountCreator.user2().id());
    CherryPickInput cin = new CherryPickInput();
    cin.message = "this need to go to stable";
    cin.destination = "stable";
    cin.keepReviewers = true;
    Map<ReviewerState, Collection<AccountInfo>> result =
        gApi.changes().id(r.getChangeId()).current().cherryPick(cin).get().reviewers;

    // 'admin' should be a reviewer as the old owner.
    // 'admin2' should be a reviewer as the old reviewer.
    // 'user' should be on CC.
    assertThat(result).containsKey(ReviewerState.REVIEWER);
    List<Integer> reviewers =
        result.get(ReviewerState.REVIEWER).stream().map(a -> a._accountId).collect(toList());
    assertThat(result).containsKey(ReviewerState.CC);
    List<Integer> ccs =
        result.get(ReviewerState.CC).stream().map(a -> a._accountId).collect(toList());
    assertThat(ccs).containsExactly(user.id().get());
    assertThat(reviewers).containsExactly(admin.id().get(), accountCreator.admin2().id().get());
  }

  @Test
  @GerritConfig(name = "accounts.visibility", value = "SAME_GROUP")
  public void cherryPickWithNonVisibleUsers() throws Exception {
    // Create a target branch for the cherry-pick.
    createBranch(BranchNameKey.create(project, "stable"));

    // Define readable names for the users we use in this test.
    TestAccount cherryPicker = user;
    TestAccount changeOwner = admin;
    TestAccount reviewer = accountCreator.user2();
    TestAccount cc =
        accountCreator.create("user3", "user3@example.com", "User3", /* displayName= */ null);
    TestAccount authorCommitter =
        accountCreator.create("user4", "user4@example.com", "User4", /* displayName= */ null);

    // Check that the cherry-picker can neither see the changeOwner, the reviewer, the cc nor the
    // authorCommitter.
    requestScopeOperations.setApiUser(cherryPicker.id());
    assertThatAccountIsNotVisible(changeOwner, reviewer, cc, authorCommitter);

    // Create the change with authorCommitter as the author and the committer.
    requestScopeOperations.setApiUser(changeOwner.id());
    PushOneCommit push = pushFactory.create(authorCommitter.newIdent(), testRepo);
    PushOneCommit.Result r = push.to("refs/for/master");
    r.assertOkStatus();

    // Check that authorCommitter was set as the author and committer.
    ChangeInfo changeInfo = gApi.changes().id(r.getChangeId()).get();
    CommitInfo commit = changeInfo.revisions.get(changeInfo.currentRevision).commit;
    assertThat(commit.author.email).isEqualTo(authorCommitter.email());
    assertThat(commit.committer.email).isEqualTo(authorCommitter.email());

    // Pushing a commit with a forged author/committer adds the author/committer as a CC.
    assertCcs(r.getChangeId(), authorCommitter);

    // Remove the author/committer as a CC because because otherwise there are two signals for CCing
    // authorCommitter on the cherry-pick change: once because they are author and committer and
    // once because they are a CC. For authorCommitter we only want to test the first signal here
    // (the second signal is covered by adding an explicit CC below).
    gApi.changes().id(r.getChangeId()).reviewer(authorCommitter.email()).remove();
    assertNoCcs(r.getChangeId());

    // Add reviewer and cc.
    ReviewInput reviewerInput = ReviewInput.approve();
    reviewerInput.reviewer(reviewer.email());
    reviewerInput.cc(cc.email());
    gApi.changes().id(r.getChangeId()).current().review(reviewerInput);

    // Approve and submit the change.
    gApi.changes().id(r.getChangeId()).current().review(ReviewInput.approve());
    gApi.changes().id(r.getChangeId()).current().submit();

    // Cherry-pick the change.
    requestScopeOperations.setApiUser(cherryPicker.id());
    CherryPickInput cherryPickInput = new CherryPickInput();
    cherryPickInput.message = "Cherry-pick to stable branch";
    cherryPickInput.destination = "stable";
    cherryPickInput.keepReviewers = true;
    String cherryPickChangeId =
        gApi.changes().id(r.getChangeId()).current().cherryPick(cherryPickInput).get().id;

    // Cherry-pick doesn't check the visibility of explicit reviewers/CCs. Since the cherry-picker
    // can see the cherry-picked change, they can also see its reviewers/CCs. This means preserving
    // them on the cherry-pick change doesn't expose their account existence and it's OK to keep
    // them even if their accounts are not visible to the cherry-picker.
    // In contrast to this for implicit CCs that are added for the author/committer the account
    // visibility is checked, but if their accounts are not visible the CC is silently dropped (so
    // that the cherry-pick request can still succeed). Since in this case authorCommitter is not
    // visible, we expect that CCing them is being dropped and hence authorCommitter is not returned
    // as a CC here. The reason that the visibility for author/committer must be checked is that
    // author/committer may not match a Gerrit account (if they are forged). This means by seeing
    // the author/committer on the cherry-picked change, it's not possible to deduce that these
    // Gerrit accounts exists, but if they would be added as a CC on the cherry-pick change even if
    // they are not visible the account existence would be exposed.
    assertReviewers(cherryPickChangeId, changeOwner, reviewer);
    assertCcs(cherryPickChangeId, cc);
  }

  @Test
  @GerritConfig(name = "repository.*.defaultConfig", value = "receive.rejectImplicitMerges=false")
  public void cherryPickToMergedChangeRevision() throws Exception {
    createBranch(BranchNameKey.create(project, "foo"));

    PushOneCommit.Result dstChange = createChange(testRepo, "foo", SUBJECT, "b.txt", "b", "t");
    dstChange.assertOkStatus();

    merge(dstChange);

    PushOneCommit.Result result = createChange(testRepo, "foo", SUBJECT, "b.txt", "c", "t");
    result.assertOkStatus();
    merge(result);

    PushOneCommit.Result srcChange = createChange();

    CherryPickInput input = new CherryPickInput();
    input.destination = "foo";
    input.base = dstChange.getCommit().name();
    input.message = srcChange.getCommit().getFullMessage();
    ChangeInfo changeInfo =
        gApi.changes().id(srcChange.getChangeId()).current().cherryPick(input).get();
    assertCherryPickResult(changeInfo, input, srcChange.getChangeId());
  }

  @Test
  public void cherryPickToOpenChangeRevision() throws Exception {
    createBranch(BranchNameKey.create(project, "foo"));

    PushOneCommit.Result dstChange = createChange(testRepo, "foo", SUBJECT, "b.txt", "b", "t");
    dstChange.assertOkStatus();

    PushOneCommit.Result srcChange = createChange();

    CherryPickInput input = new CherryPickInput();
    input.destination = "foo";
    input.base = dstChange.getCommit().name();
    input.message = srcChange.getCommit().getFullMessage();
    ChangeInfo changeInfo =
        gApi.changes().id(srcChange.getChangeId()).current().cherryPick(input).get();
    assertCherryPickResult(changeInfo, input, srcChange.getChangeId());
  }

  @Test
  public void cherryPickToNonVisibleChangeFails() throws Exception {
    createBranch(BranchNameKey.create(project, "foo"));

    PushOneCommit.Result dstChange = createChange(testRepo, "foo", SUBJECT, "b.txt", "b", "t");
    dstChange.assertOkStatus();

    gApi.changes().id(dstChange.getChangeId()).setPrivate(true, null);

    PushOneCommit.Result srcChange = createChange();

    CherryPickInput input = new CherryPickInput();
    input.destination = "foo";
    input.base = dstChange.getCommit().name();
    input.message = srcChange.getCommit().getFullMessage();

    requestScopeOperations.setApiUser(user.id());
    UnprocessableEntityException thrown =
        assertThrows(
            UnprocessableEntityException.class,
            () -> gApi.changes().id(srcChange.getChangeId()).current().cherryPick(input).get());
    assertThat(thrown)
        .hasMessageThat()
        .contains(String.format("Commit %s does not exist on branch refs/heads/foo", input.base));
  }

  @Test
  public void cherryPickToAbandonedChangeFails() throws Exception {
    PushOneCommit.Result change1 = createChange();
    PushOneCommit.Result change2 = createChange();
    gApi.changes().id(change2.getChangeId()).abandon();

    CherryPickInput input = new CherryPickInput();
    input.destination = "master";
    input.base = change2.getCommit().name();
    input.message = change1.getCommit().getFullMessage();

    ResourceConflictException thrown =
        assertThrows(
            ResourceConflictException.class,
            () -> gApi.changes().id(change1.getChangeId()).current().cherryPick(input));
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            String.format(
                "Change %s with commit %s is abandoned",
                change2.getChange().getId().get(), input.base));
  }

  @Test
  public void cherryPickWithInvalidBaseFails() throws Exception {
    PushOneCommit.Result change1 = createChange();

    CherryPickInput input = new CherryPickInput();
    input.destination = "master";
    input.base = "invalid-sha1";
    input.message = change1.getCommit().getFullMessage();

    BadRequestException thrown =
        assertThrows(
            BadRequestException.class,
            () -> gApi.changes().id(change1.getChangeId()).current().cherryPick(input));
    assertThat(thrown)
        .hasMessageThat()
        .contains(String.format("Base %s doesn't represent a valid SHA-1", input.base));
  }

  @Test
  public void cherryPickToCommitWithoutChangeId() throws Exception {
    RevCommit commit1 = createNewCommitWithoutChangeId("refs/heads/foo", "a.txt", "content 1");

    createNewCommitWithoutChangeId("refs/heads/foo", "a.txt", "content 2");

    PushOneCommit.Result srcChange = createChange("subject", "b.txt", "b");
    srcChange.assertOkStatus();

    CherryPickInput input = new CherryPickInput();
    input.destination = "foo";
    input.base = commit1.name();
    input.message = srcChange.getCommit().getFullMessage();
    ChangeInfo changeInfo =
        gApi.changes().id(srcChange.getChangeId()).current().cherryPick(input).get();
    assertCherryPickResult(changeInfo, input, srcChange.getChangeId());
  }

  @Test
  public void cherryPickToNonExistingBranch() throws Exception {
    PushOneCommit.Result result = createChange();

    CherryPickInput input = new CherryPickInput();
    input.message = "foo bar";
    input.destination = "non-existing";
    // TODO(ekempin): This should rather result in an UnprocessableEntityException.
    BadRequestException thrown =
        assertThrows(
            BadRequestException.class,
            () -> gApi.changes().id(result.getChangeId()).current().cherryPick(input));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(
            String.format("Branch %s does not exist.", RefNames.REFS_HEADS + input.destination));
  }

  @Test
  public void cherryPickToNonExistingBaseCommit() throws Exception {
    createBranch(BranchNameKey.create(project, "foo"));
    PushOneCommit.Result result = createChange();

    CherryPickInput input = new CherryPickInput();
    input.message = "foo bar";
    input.destination = "foo";
    input.base = "deadbeefdeadbeefdeadbeefdeadbeefdeadbeef";
    UnprocessableEntityException thrown =
        assertThrows(
            UnprocessableEntityException.class,
            () -> gApi.changes().id(result.getChangeId()).current().cherryPick(input));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(String.format("Base %s doesn't exist", input.base));
  }

  @Test
  public void getRelatedCherryPicks() throws Exception {
    PushOneCommit.Result r1 = createChange(SUBJECT, "a.txt", "a");
    PushOneCommit.Result r2 = createChange(SUBJECT, "b.txt", "b");

    String branch = "foo";
    // Create target branch to cherry-pick to.
    gApi.projects().name(project.get()).branch(branch).create(new BranchInput());

    CherryPickInput input = new CherryPickInput();
    input.message = "message";
    input.destination = branch;
    ChangeInfo firstCherryPickResult =
        gApi.changes().id(r1.getChangeId()).current().cherryPickAsInfo(input);

    input.base = gApi.changes().id(firstCherryPickResult.changeId).current().commit(false).commit;
    ChangeInfo secondCherryPickResult =
        gApi.changes().id(r2.getChangeId()).current().cherryPickAsInfo(input);
    assertThat(gApi.changes().id(firstCherryPickResult.changeId).current().related().changes)
        .hasSize(2);
    assertThat(gApi.changes().id(secondCherryPickResult.changeId).current().related().changes)
        .hasSize(2);
  }

  @Test
  public void cherryPickOnMergedChangeIsNotRelated() throws Exception {
    PushOneCommit.Result r1 = createChange(SUBJECT, "a.txt", "a");
    PushOneCommit.Result r2 = createChange(SUBJECT, "b.txt", "b");

    String branch = "foo";
    // Create target branch to cherry-pick to.
    gApi.projects().name(project.get()).branch(branch).create(new BranchInput());

    CherryPickInput input = new CherryPickInput();
    input.message = "message";
    input.destination = branch;
    ChangeInfo firstCherryPickResult =
        gApi.changes().id(r1.getChangeId()).current().cherryPickAsInfo(input);

    gApi.changes().id(firstCherryPickResult.id).current().review(ReviewInput.approve());
    gApi.changes().id(firstCherryPickResult.id).current().submit();

    input.base = gApi.changes().id(firstCherryPickResult.changeId).current().commit(false).commit;
    ChangeInfo secondCherryPickResult =
        gApi.changes().id(r2.getChangeId()).current().cherryPickAsInfo(input);
    assertThat(gApi.changes().id(firstCherryPickResult.changeId).current().related().changes)
        .hasSize(0);
    assertThat(gApi.changes().id(secondCherryPickResult.changeId).current().related().changes)
        .hasSize(0);
  }

  @Test
  @UseClockStep
  public void cherryPickSetsReadyChangeOnNewPatchset() throws Exception {
    PushOneCommit.Result result = pushTo("refs/for/master");
    CherryPickInput input = new CherryPickInput();
    input.destination = "foo";
    gApi.projects().name(project.get()).branch(input.destination).create(new BranchInput());
    ChangeApi originalChange = gApi.changes().id(project.get() + "~master~" + result.getChangeId());

    ChangeApi cherryPick = originalChange.revision(result.getCommit().name()).cherryPick(input);
    String firstCherryPickChangeId = cherryPick.id();
    cherryPick.setWorkInProgress();
    gApi.changes()
        .id(project.get() + "~master~" + result.getChangeId())
        .revision(result.getCommit().name())
        .cherryPick(input);

    ChangeInfo secondCherryPickResult =
        gApi.changes().id(firstCherryPickChangeId).get(ALL_REVISIONS);
    assertThat(secondCherryPickResult.revisions).hasSize(2);
    assertThat(secondCherryPickResult.workInProgress).isNull();
  }

  @Test
  public void canRebase() throws Exception {
    PushOneCommit push = pushFactory.create(admin.newIdent(), testRepo);
    PushOneCommit.Result r1 = push.to("refs/for/master");
    merge(r1);

    push = pushFactory.create(admin.newIdent(), testRepo);
    PushOneCommit.Result r2 = push.to("refs/for/master");
    boolean canRebase =
        gApi.changes().id(r2.getChangeId()).revision(r2.getCommit().name()).canRebase();
    assertThat(canRebase).isFalse();
    merge(r2);

    testRepo.reset(r1.getCommit());
    push = pushFactory.create(admin.newIdent(), testRepo);
    PushOneCommit.Result r3 = push.to("refs/for/master");

    canRebase = gApi.changes().id(r3.getChangeId()).revision(r3.getCommit().name()).canRebase();
    assertThat(canRebase).isTrue();
  }

  @Test
  public void setUnsetReviewedFlag() throws Exception {
    PushOneCommit push = pushFactory.create(admin.newIdent(), testRepo);
    PushOneCommit.Result r = push.to("refs/for/master");

    gApi.changes().id(r.getChangeId()).current().setReviewed(PushOneCommit.FILE_NAME, true);

    assertThat(Iterables.getOnlyElement(gApi.changes().id(r.getChangeId()).current().reviewed()))
        .isEqualTo(PushOneCommit.FILE_NAME);

    gApi.changes().id(r.getChangeId()).current().setReviewed(PushOneCommit.FILE_NAME, false);

    assertThat(gApi.changes().id(r.getChangeId()).current().reviewed()).isEmpty();
  }

  @Test
  public void setReviewedFlagWithMultiplePatchSets() throws Exception {
    PushOneCommit push = pushFactory.create(admin.newIdent(), testRepo);
    PushOneCommit.Result r1 = push.to("refs/for/master");

    gApi.changes().id(r1.getChangeId()).current().setReviewed(PushOneCommit.FILE_NAME, true);

    /** Amending the change will result in the file being un-reviewed in the latest patchset */
    PushOneCommit.Result r2 = amendChange(r1.getChangeId());

    assertThat(gApi.changes().id(r2.getChangeId()).current().reviewed()).isEmpty();

    gApi.changes().id(r2.getChangeId()).current().setReviewed(PushOneCommit.FILE_NAME, true);

    assertThat(Iterables.getOnlyElement(gApi.changes().id(r2.getChangeId()).current().reviewed()))
        .isEqualTo(PushOneCommit.FILE_NAME);
  }

  @Test
  public void setUnsetReviewedFlagByFileApi() throws Exception {
    PushOneCommit push = pushFactory.create(admin.newIdent(), testRepo);
    PushOneCommit.Result r = push.to("refs/for/master");

    gApi.changes().id(r.getChangeId()).current().file(PushOneCommit.FILE_NAME).setReviewed(true);

    assertThat(Iterables.getOnlyElement(gApi.changes().id(r.getChangeId()).current().reviewed()))
        .isEqualTo(PushOneCommit.FILE_NAME);

    gApi.changes().id(r.getChangeId()).current().file(PushOneCommit.FILE_NAME).setReviewed(false);

    assertThat(gApi.changes().id(r.getChangeId()).current().reviewed()).isEmpty();
  }

  @Test
  @GerritConfig(
      name = "change.mergeabilityComputationBehavior",
      value = "API_REF_UPDATED_AND_CHANGE_REINDEX")
  public void mergeable() throws Exception {
    ObjectId initial = repo().exactRef(HEAD).getLeaf().getObjectId();

    PushOneCommit push1 =
        pushFactory.create(
            admin.newIdent(),
            testRepo,
            PushOneCommit.SUBJECT,
            PushOneCommit.FILE_NAME,
            "push 1 content");

    PushOneCommit.Result r1 = push1.to("refs/for/master");
    assertMergeable(r1.getChangeId(), true);
    merge(r1);

    // Reset client HEAD to initial so the new change is a merge conflict.
    testRepo.reset(initial);

    PushOneCommit push2 =
        pushFactory.create(
            admin.newIdent(),
            testRepo,
            PushOneCommit.SUBJECT,
            PushOneCommit.FILE_NAME,
            "new contents");
    PushOneCommit.Result r2 = push2.to("refs/for/master");
    Change.Id id2 = r2.getChange().getId();
    assertMergeable(r2.getChangeId(), false);

    // Search shows change is not mergeable.
    Callable<List<ChangeInfo>> search =
        () -> gApi.changes().query("is:mergeable change:" + r2.getChangeId()).get();
    assertThat(search.call()).isEmpty();

    // Make the same change in a separate commit and update server HEAD behind Gerrit's back, which
    // will not reindex any open changes.
    try (Repository repo = repoManager.openRepository(project);
        TestRepository<Repository> tr = new TestRepository<>(repo)) {
      String ref = "refs/heads/master";
      assertThat(repo.exactRef(ref).getObjectId()).isEqualTo(r1.getCommit());
      tr.update(ref, tr.getRevWalk().parseCommit(initial));
      tr.branch(ref)
          .commit()
          .message("Side update")
          .add(PushOneCommit.FILE_NAME, "new contents")
          .create();
    }

    // Search shows change is still not mergeable.
    assertThat(search.call()).isEmpty();

    // Using the API returns the correct value, and reindexes as well.
    CountDownLatch reindexed = new CountDownLatch(1);
    ChangeIndexedListener listener =
        new ChangeIndexedListener() {
          @Override
          public void onChangeIndexed(String projectName, int id) {
            if (id == id2.get()) {
              reindexed.countDown();
            }
          }

          @Override
          public void onChangeDeleted(int id) {}
        };

    try (Registration registration = extensionRegistry.newRegistration().add(listener)) {
      assertMergeable(r2.getChangeId(), true);
      reindexed.await();
    }

    List<ChangeInfo> changes = search.call();
    assertThat(changes).hasSize(1);
    assertThat(changes.get(0).changeId).isEqualTo(r2.getChangeId());
    assertThat(changes.get(0).mergeable).isEqualTo(Boolean.TRUE);
  }

  @Test
  public void mergeableOtherBranches() throws Exception {
    String head = getHead(repo(), HEAD).name();
    createBranchWithRevision(BranchNameKey.create(project, "mergeable-other-branch"), head);
    createBranchWithRevision(BranchNameKey.create(project, "ignored"), head);
    PushOneCommit.Result change1 = createChange();
    try (ProjectConfigUpdate u = updateProject(project)) {
      u.getConfig()
          .setBranchOrderSection(
              BranchOrderSection.create(
                  ImmutableList.of("master", "nonexistent", "mergeable-other-branch")));
      u.save();
    }

    MergeableInfo mergeableInfo =
        gApi.changes().id(change1.getChangeId()).current().mergeableOtherBranches();
    assertThat(mergeableInfo.mergeableInto).containsExactly("mergeable-other-branch");
  }

  @Test
  public void files() throws Exception {
    PushOneCommit.Result r = createChange();
    Map<String, FileInfo> files =
        gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).files();
    assertThat(files.keySet()).containsExactly(FILE_NAME, COMMIT_MSG);
  }

  @Test
  public void filesOnMergeCommitChange() throws Exception {
    PushOneCommit.Result r = createMergeCommitChange("refs/for/master");

    // List files against auto-merge
    assertThat(gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).files().keySet())
        .containsExactly(COMMIT_MSG, MERGE_LIST, "foo", "bar");

    // List files against parent 1
    assertThat(gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).files(1).keySet())
        .containsExactly(COMMIT_MSG, MERGE_LIST, "bar");

    // List files against parent 2
    assertThat(gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).files(2).keySet())
        .containsExactly(COMMIT_MSG, MERGE_LIST, "foo");
  }

  @Test
  public void filesOnMergeCommitChangeWithInvalidParent() throws Exception {
    PushOneCommit.Result r = createMergeCommitChange("refs/for/master");

    BadRequestException thrown =
        assertThrows(
            BadRequestException.class,
            () -> gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).files(3));
    assertThat(thrown).hasMessageThat().isEqualTo("invalid parent number: 3");
    thrown =
        assertThrows(
            BadRequestException.class,
            () -> gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).files(-1));
    assertThat(thrown).hasMessageThat().isEqualTo("invalid parent number: -1");
  }

  @Test
  public void listFilesWithInvalidParent() throws Exception {
    PushOneCommit.Result result1 = createChange();
    String changeId = result1.getChangeId();
    PushOneCommit.Result result2 = amendChange(changeId, SUBJECT, "b.txt", "b");
    String revId2 = result2.getCommit().name();

    BadRequestException thrown =
        assertThrows(
            BadRequestException.class, () -> gApi.changes().id(changeId).revision(revId2).files(2));
    assertThat(thrown).hasMessageThat().isEqualTo("invalid parent number: 2");

    thrown =
        assertThrows(
            BadRequestException.class,
            () -> gApi.changes().id(changeId).revision(revId2).files(-1));
    assertThat(thrown).hasMessageThat().isEqualTo("invalid parent number: -1");
  }

  @Test
  public void listFilesOnDifferentBases() throws Exception {
    RevCommit initialCommit = getHead(repo(), "HEAD");

    PushOneCommit.Result result1 = createChange();
    String changeId = result1.getChangeId();
    PushOneCommit.Result result2 = amendChange(changeId, SUBJECT, "b.txt", "b");
    PushOneCommit.Result result3 = amendChange(changeId, SUBJECT, "c.txt", "c");

    String revId1 = result1.getCommit().name();
    String revId2 = result2.getCommit().name();
    String revId3 = result3.getCommit().name();

    assertThat(gApi.changes().id(changeId).revision(revId1).files(null).keySet())
        .containsExactly(COMMIT_MSG, "a.txt");
    assertThat(gApi.changes().id(changeId).revision(revId2).files(null).keySet())
        .containsExactly(COMMIT_MSG, "a.txt", "b.txt");
    assertThat(gApi.changes().id(changeId).revision(revId3).files(null).keySet())
        .containsExactly(COMMIT_MSG, "a.txt", "b.txt", "c.txt");

    assertThat(gApi.changes().id(changeId).revision(revId2).files(revId1).keySet())
        .containsExactly(COMMIT_MSG, "b.txt");
    assertThat(gApi.changes().id(changeId).revision(revId3).files(revId1).keySet())
        .containsExactly(COMMIT_MSG, "b.txt", "c.txt");
    assertThat(gApi.changes().id(changeId).revision(revId3).files(revId2).keySet())
        .containsExactly(COMMIT_MSG, "c.txt");

    ResourceNotFoundException thrown =
        assertThrows(
            ResourceNotFoundException.class,
            () -> gApi.changes().id(changeId).revision(revId3).files(initialCommit.getName()));
    assertThat(thrown).hasMessageThat().contains(initialCommit.getName());

    String invalidRev = "deadbeef";
    thrown =
        assertThrows(
            ResourceNotFoundException.class,
            () -> gApi.changes().id(changeId).revision(revId3).files(invalidRev));
    assertThat(thrown).hasMessageThat().contains(invalidRev);
  }

  @Test
  public void queryRevisionFiles() throws Exception {
    ImmutableMap<String, String> files =
        ImmutableMap.of("file1.txt", "content 1", "file2.txt", "content 2");
    PushOneCommit.Result result =
        pushFactory.create(admin.newIdent(), testRepo, SUBJECT, files).to("refs/for/master");
    result.assertOkStatus();
    String changeId = result.getChangeId();

    assertThat(gApi.changes().id(changeId).current().queryFiles("file1.txt"))
        .containsExactly("file1.txt");
    assertThat(gApi.changes().id(changeId).current().queryFiles("file2.txt"))
        .containsExactly("file2.txt");
    assertThat(gApi.changes().id(changeId).current().queryFiles("file1"))
        .containsExactly("file1.txt");
    assertThat(gApi.changes().id(changeId).current().queryFiles("file2"))
        .containsExactly("file2.txt");
    assertThat(gApi.changes().id(changeId).current().queryFiles("file"))
        .containsExactly("file1.txt", "file2.txt");
    assertThat(gApi.changes().id(changeId).current().queryFiles(""))
        .containsExactly("file1.txt", "file2.txt");
  }

  @Test
  public void description() throws Exception {
    PushOneCommit.Result r = createChange();
    assertDescription(r, "");

    // set description
    gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).description("foo");
    assertDescription(r, "foo");
    assertThat(Iterables.getLast(gApi.changes().id(r.getChangeId()).get().messages).message)
        .isEqualTo("Description of patch set 1 set to \"foo\"");

    // update description
    gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).description("bar");
    assertDescription(r, "bar");
    assertThat(Iterables.getLast(gApi.changes().id(r.getChangeId()).get().messages).message)
        .isEqualTo("Description of patch set 1 changed to \"bar\"");

    // remove description
    gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).description("");
    assertDescription(r, "");
    assertThat(Iterables.getLast(gApi.changes().id(r.getChangeId()).get().messages).message)
        .isEqualTo("Description \"bar\" removed from patch set 1");
  }

  @Test
  public void targetBranch_isSetForEachPatchSet() throws Exception {
    createBranch(BranchNameKey.create(project, "foo"));
    createBranch(BranchNameKey.create(project, "bar"));

    // Upload five patch-sets: patch-set 1 is destined for branch master, patch-set 2 destined for
    // branch foo, patch-set 3 with no change, and patch-set 4 for branch bar and patch-set 5 with
    // no change in branch. Make sure the targetBranch field is set correctly on each revision.

    // PS1 - branch = master
    PushOneCommit.Result r1 = createChange();
    // PS2 - branch = foo
    PushOneCommit.Result r2 = amendChange(r1.getChangeId());
    move(r1.getChangeId(), "foo");
    // PS3 - branch = foo
    PushOneCommit.Result r3 = amendChange(r1.getChangeId(), "refs/for/foo", admin, testRepo);
    // PS4 - branch = bar
    PushOneCommit.Result r4 = amendChange(r1.getChangeId(), "refs/for/foo", admin, testRepo);
    move(r1.getChangeId(), "bar");
    // PS5 - branch = bar
    PushOneCommit.Result r5 = amendChange(r1.getChangeId(), "refs/for/bar", admin, testRepo);

    Map<String, RevisionInfo> revisions = gApi.changes().id(r1.getChangeId()).get().revisions;
    assertThat(revisions).hasSize(5);
    assertThat(revisions.get(r1.getCommit().name()).branch).isEqualTo("refs/heads/master");
    assertThat(revisions.get(r2.getCommit().name()).branch).isEqualTo("refs/heads/foo");
    assertThat(revisions.get(r3.getCommit().name()).branch).isEqualTo("refs/heads/foo");
    assertThat(revisions.get(r4.getCommit().name()).branch).isEqualTo("refs/heads/bar");
    assertThat(revisions.get(r5.getCommit().name()).branch).isEqualTo("refs/heads/bar");
  }

  @Test
  public void setDescriptionNotAllowedWithoutPermission() throws Exception {
    PushOneCommit.Result r = createChange();
    assertDescription(r, "");
    requestScopeOperations.setApiUser(user.id());
    AuthException thrown =
        assertThrows(
            AuthException.class,
            () ->
                gApi.changes()
                    .id(r.getChangeId())
                    .revision(r.getCommit().name())
                    .description("test"));
    assertThat(thrown).hasMessageThat().contains("edit description not permitted");
  }

  @Test
  public void setDescriptionAllowedWithPermission() throws Exception {
    PushOneCommit.Result r = createChange();
    assertDescription(r, "");
    projectOperations
        .project(project)
        .forUpdate()
        .add(allow(Permission.OWNER).ref("refs/heads/master").group(REGISTERED_USERS))
        .update();
    requestScopeOperations.setApiUser(user.id());
    gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).description("test");
    assertDescription(r, "test");
  }

  private void assertDescription(PushOneCommit.Result r, String expected) throws Exception {
    assertThat(gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).description())
        .isEqualTo(expected);
  }

  @Test
  public void content() throws Exception {
    PushOneCommit.Result r = createChange();
    assertContent(r, FILE_NAME, FILE_CONTENT);
    assertContent(r, COMMIT_MSG, r.getCommit().getFullMessage());
  }

  @Test
  @GerritConfig(name = "change.maxFileSizeDownload", value = "10")
  public void content_maxFileSizeDownload() throws Exception {
    ImmutableMap<String, String> files =
        ImmutableMap.of("dir/file1.txt", " 9 bytes ", "dir/file2.txt", "11 bytes xx");
    PushOneCommit.Result result =
        pushFactory.create(admin.newIdent(), testRepo, SUBJECT, files).to("refs/for/master");
    result.assertOkStatus();

    // 9 bytes should be fine, because the limit is 10 bytes.
    assertContent(result, "dir/file1.txt", " 9 bytes ");

    // 11 bytes should throw, because the limit is 10 bytes.
    BadRequestException exception =
        assertThrows(
            BadRequestException.class,
            () ->
                gApi.changes()
                    .id(result.getChangeId())
                    .revision(result.getCommit().name())
                    .file("dir/file2.txt")
                    .content());
    assertThat(exception)
        .hasMessageThat()
        .isEqualTo(
            "File too big. File size: 11 bytes. Configured 'maxFileSizeDownload' limit: 10 bytes.");
  }

  @Test
  public void patchsetLevelContentDoesNotExist() throws Exception {
    PushOneCommit.Result change = createChange();
    assertThrows(
        ResourceNotFoundException.class,
        () ->
            gApi.changes()
                .id(change.getChangeId())
                .revision(change.getCommit().name())
                .file(PATCHSET_LEVEL)
                .content());
  }

  @Test
  public void cannotGetContentOfDirectory() throws Exception {
    ImmutableMap<String, String> files = ImmutableMap.of("dir/file1.txt", "content 1");
    PushOneCommit.Result result =
        pushFactory.create(admin.newIdent(), testRepo, SUBJECT, files).to("refs/for/master");
    result.assertOkStatus();

    assertThrows(
        BadRequestException.class,
        () ->
            gApi.changes()
                .id(result.getChangeId())
                .revision(result.getCommit().name())
                .file("dir")
                .content());
  }

  @Test
  public void contentType() throws Exception {
    PushOneCommit.Result r = createChange();

    String endPoint =
        "/changes/"
            + r.getChangeId()
            + "/revisions/"
            + r.getCommit().name()
            + "/files/"
            + FILE_NAME
            + "/content";
    RestResponse response = adminRestSession.head(endPoint);
    response.assertOK();
    assertThat(response.getContentType()).startsWith("text/plain");
    assertThat(response.hasContent()).isFalse();
  }

  @Test
  public void commit() throws Exception {
    WebLinkInfo expectedPatchSetLinkInfo = new WebLinkInfo("foo", "imageUrl", "url");
    PatchSetWebLink patchSetLink =
        new PatchSetWebLink() {
          @Override
          public WebLinkInfo getPatchSetWebLink(
              String projectName, String commit, String commitMessage, String branchName) {
            return expectedPatchSetLinkInfo;
          }
        };
    WebLinkInfo expectedResolveConflictsLinkInfo = new WebLinkInfo("bar", "img", "resolve");
    ResolveConflictsWebLink resolveConflictsLink =
        new ResolveConflictsWebLink() {
          @Override
          public WebLinkInfo getResolveConflictsWebLink(
              String projectName, String commit, String commitMessage, String branchName) {
            return expectedResolveConflictsLinkInfo;
          }
        };
    try (Registration registration =
        extensionRegistry.newRegistration().add(patchSetLink).add(resolveConflictsLink)) {
      PushOneCommit.Result r = createChange();
      RevCommit c = r.getCommit();

      CommitInfo commitInfo = gApi.changes().id(r.getChangeId()).current().commit(false);
      assertThat(commitInfo.commit).isEqualTo(c.name());
      assertPersonIdent(commitInfo.author, c.getAuthorIdent());
      assertPersonIdent(commitInfo.committer, c.getCommitterIdent());
      assertThat(commitInfo.message).isEqualTo(c.getFullMessage());
      assertThat(commitInfo.subject).isEqualTo(c.getShortMessage());
      assertThat(commitInfo.parents).hasSize(1);
      assertThat(Iterables.getOnlyElement(commitInfo.parents).commit)
          .isEqualTo(c.getParent(0).name());
      assertThat(commitInfo.webLinks).isNull();

      commitInfo = gApi.changes().id(r.getChangeId()).current().commit(true);
      assertThat(commitInfo.webLinks).hasSize(1);
      WebLinkInfo patchSetLinkInfo = Iterables.getOnlyElement(commitInfo.webLinks);
      assertThat(patchSetLinkInfo.name).isEqualTo(expectedPatchSetLinkInfo.name);
      assertThat(patchSetLinkInfo.imageUrl).isEqualTo(expectedPatchSetLinkInfo.imageUrl);
      assertThat(patchSetLinkInfo.url).isEqualTo(expectedPatchSetLinkInfo.url);

      assertThat(commitInfo.resolveConflictsWebLinks).hasSize(1);
      WebLinkInfo resolveCommentsLinkInfo =
          Iterables.getOnlyElement(commitInfo.resolveConflictsWebLinks);
      assertThat(resolveCommentsLinkInfo.name).isEqualTo(expectedResolveConflictsLinkInfo.name);
      assertThat(resolveCommentsLinkInfo.imageUrl)
          .isEqualTo(expectedResolveConflictsLinkInfo.imageUrl);
      assertThat(resolveCommentsLinkInfo.url).isEqualTo(expectedResolveConflictsLinkInfo.url);
    }
  }

  // TODO(issue-40014498): Migrate timestamp fields in *Info/*Input classes from type Timestamp to
  // Instant
  @SuppressWarnings("JdkObsolete")
  private void assertPersonIdent(GitPerson gitPerson, PersonIdent expectedIdent) {
    assertThat(gitPerson.name).isEqualTo(expectedIdent.getName());
    assertThat(gitPerson.email).isEqualTo(expectedIdent.getEmailAddress());
    assertThat(gitPerson.date.getTime()).isEqualTo(expectedIdent.getWhenAsInstant().toEpochMilli());
    assertThat(gitPerson.tz).isEqualTo(expectedIdent.getTimeZoneOffset());
  }

  private void assertMergeable(String id, boolean expected) throws Exception {
    MergeableInfo m = gApi.changes().id(id).current().mergeable();
    assertThat(m.mergeable).isEqualTo(expected);
    assertThat(m.submitType).isEqualTo(SubmitType.MERGE_IF_NECESSARY);
    assertThat(m.mergeableInto).isNull();
    ChangeInfo c = gApi.changes().id(id).info();
    assertThat(c.mergeable).isEqualTo(expected);
  }

  @Test
  public void drafts() throws Exception {
    PushOneCommit.Result r = createChange();
    DraftInput in = new DraftInput();
    in.line = 1;
    in.message = "nit: trailing whitespace";
    in.path = FILE_NAME;

    DraftApi draftApi =
        gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).createDraft(in);
    assertThat(draftApi.get().message).isEqualTo(in.message);
    assertThat(
            gApi.changes()
                .id(r.getChangeId())
                .revision(r.getCommit().name())
                .draft(draftApi.get().id)
                .get()
                .message)
        .isEqualTo(in.message);
    assertThat(gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).drafts())
        .hasSize(1);

    in.message = "good catch!";
    assertThat(
            gApi.changes()
                .id(r.getChangeId())
                .revision(r.getCommit().name())
                .draft(draftApi.get().id)
                .update(in)
                .message)
        .isEqualTo(in.message);

    assertThat(
            gApi.changes()
                .id(r.getChangeId())
                .revision(r.getCommit().name())
                .draft(draftApi.get().id)
                .get()
                .author
                .email)
        .isEqualTo(admin.email());

    draftApi.delete();
    assertThat(gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).drafts())
        .isEmpty();
  }

  @Test
  public void comments() throws Exception {
    PushOneCommit.Result r = createChange();
    CommentInput in = new CommentInput();
    in.line = 1;
    in.message = "nit: trailing whitespace";
    in.path = FILE_NAME;
    ReviewInput reviewInput = new ReviewInput();
    Map<String, List<CommentInput>> comments = new HashMap<>();
    comments.put(FILE_NAME, Collections.singletonList(in));
    reviewInput.comments = comments;
    reviewInput.message = "comment test";
    gApi.changes().id(r.getChangeId()).current().review(reviewInput);

    Map<String, List<CommentInfo>> out =
        gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).comments();
    assertThat(out).hasSize(1);
    CommentInfo comment = Iterables.getOnlyElement(out.get(FILE_NAME));
    assertThat(comment.message).isEqualTo(in.message);
    assertThat(comment.author.email).isEqualTo(admin.email());
    assertThat(comment.path).isNull();

    List<CommentInfo> list =
        gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).commentsAsList();
    assertThat(list).hasSize(1);

    CommentInfo comment2 = list.get(0);
    assertThat(comment2.path).isEqualTo(FILE_NAME);
    assertThat(comment2.line).isEqualTo(comment.line);
    assertThat(comment2.message).isEqualTo(comment.message);
    assertThat(comment2.author.email).isEqualTo(comment.author.email);

    assertThat(
            gApi.changes()
                .id(r.getChangeId())
                .revision(r.getCommit().name())
                .comment(comment.id)
                .get()
                .message)
        .isEqualTo(in.message);
  }

  @Test
  public void commentOnNonExistingFile() throws Exception {
    PushOneCommit.Result r1 = createChange();
    PushOneCommit.Result r2 = updateChange(r1, "new content");
    CommentInput in = new CommentInput();
    in.line = 1;
    in.message = "nit: trailing whitespace";
    in.path = "non-existing.txt";
    ReviewInput reviewInput = new ReviewInput();
    Map<String, List<CommentInput>> comments = new HashMap<>();
    comments.put("non-existing.txt", Collections.singletonList(in));
    reviewInput.comments = comments;
    reviewInput.message = "comment test";

    BadRequestException thrown =
        assertThrows(
            BadRequestException.class,
            () -> gApi.changes().id(r2.getChangeId()).revision(1).review(reviewInput));
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            String.format("not found in revision %d,1", r2.getChange().change().getId().get()));
  }

  @Test
  public void patch() throws Exception {
    PushOneCommit.Result r = createChange();
    ChangeApi changeApi = gApi.changes().id(r.getChangeId());
    BinaryResult bin = changeApi.revision(r.getCommit().name()).patch();
    ByteArrayOutputStream os = new ByteArrayOutputStream();
    bin.writeTo(os);
    String res = new String(os.toByteArray(), UTF_8);
    ChangeInfo change = changeApi.get();
    RevisionInfo rev = change.revisions.get(change.currentRevision);
    DateFormat df = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US);
    String date = df.format(rev.commit.author.date);
    assertThat(res).isEqualTo(String.format(PATCH, r.getCommit().name(), date, r.getChangeId()));
  }

  @Test
  public void patchWithPath() throws Exception {
    PushOneCommit.Result r = createChange();
    ChangeApi changeApi = gApi.changes().id(r.getChangeId());
    BinaryResult bin = changeApi.revision(r.getCommit().name()).patch(FILE_NAME);
    ByteArrayOutputStream os = new ByteArrayOutputStream();
    bin.writeTo(os);
    String res = new String(os.toByteArray(), UTF_8);
    assertThat(res).isEqualTo(PATCH_FILE_ONLY);

    ResourceNotFoundException thrown =
        assertThrows(
            ResourceNotFoundException.class,
            () -> changeApi.revision(r.getCommit().name()).patch("nonexistent-file"));
    assertThat(thrown).hasMessageThat().contains("File not found: nonexistent-file.");
  }

  @Test
  public void actions() throws Exception {
    PushOneCommit.Result r = createChange();
    assertThat(current(r).actions().keySet())
        .containsExactly("cherrypick", "description", "rebase");

    current(r).review(ReviewInput.approve());
    assertThat(current(r).actions().keySet())
        .containsExactly("submit", "cherrypick", "description", "rebase");

    current(r).submit();
    assertThat(current(r).actions().keySet()).containsExactly("cherrypick");
  }

  @Test
  public void deleteVoteOnNonCurrentPatchSet() throws Exception {
    PushOneCommit.Result r = createChange(); // patch set 1
    gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).review(ReviewInput.approve());

    // patch set 2
    amendChange(r.getChangeId());

    // code-review
    requestScopeOperations.setApiUser(user.id());
    recommend(r.getChangeId());

    // check if it's blocked to delete a vote on a non-current patch set.
    requestScopeOperations.setApiUser(admin.id());
    MethodNotAllowedException thrown =
        assertThrows(
            MethodNotAllowedException.class,
            () ->
                gApi.changes()
                    .id(r.getChangeId())
                    .revision(r.getCommit().getName())
                    .reviewer(user.id().toString())
                    .deleteVote(LabelId.CODE_REVIEW));
    assertThat(thrown).hasMessageThat().contains("Cannot access on non-current patch set");
  }

  @Test
  public void deleteVoteOnCurrentPatchSet() throws Exception {
    PushOneCommit.Result r = createChange(); // patch set 1
    gApi.changes().id(r.getChangeId()).revision(r.getCommit().name()).review(ReviewInput.approve());

    // patch set 2
    amendChange(r.getChangeId());

    // code-review
    requestScopeOperations.setApiUser(user.id());
    recommend(r.getChangeId());

    requestScopeOperations.setApiUser(admin.id());
    gApi.changes()
        .id(r.getChangeId())
        .current()
        .reviewer(user.id().toString())
        .deleteVote(LabelId.CODE_REVIEW);

    Map<String, Short> m =
        gApi.changes().id(r.getChangeId()).current().reviewer(user.id().toString()).votes();

    assertThat(m).containsExactly(LabelId.CODE_REVIEW, Short.valueOf((short) 0));

    ChangeInfo c = gApi.changes().id(r.getChangeId()).get();
    ChangeMessageInfo message = Iterables.getLast(c.messages);
    assertThat(message.author._accountId).isEqualTo(admin.id().get());
    assertThat(message.message)
        .isEqualTo(
            String.format(
                "Removed Code-Review+1 by %s\n",
                AccountTemplateUtil.getAccountTemplate(user.id())));
    assertThat(getReviewers(c.reviewers.get(ReviewerState.REVIEWER)))
        .containsExactlyElementsIn(ImmutableSet.of(admin.id(), user.id()));
  }

  @Test
  public void listVotesByRevision() throws Exception {
    // Create patch set 1 and vote on it
    String changeId = createChange().getChangeId();
    ListMultimap<String, ApprovalInfo> votes = gApi.changes().id(changeId).current().votes();
    assertThat(votes).isEmpty();
    recommend(changeId);
    votes = gApi.changes().id(changeId).current().votes();
    assertThat(votes.keySet()).containsExactly(LabelId.CODE_REVIEW);
    List<ApprovalInfo> approvals = votes.get(LabelId.CODE_REVIEW);
    assertThat(approvals).hasSize(1);
    ApprovalInfo approval = approvals.get(0);
    assertThat(approval._accountId).isEqualTo(admin.id().get());
    assertThat(approval.email).isEqualTo(admin.email());
    assertThat(approval.username).isEqualTo(admin.username());

    // Also vote on it with another user
    requestScopeOperations.setApiUser(user.id());
    gApi.changes().id(changeId).current().review(ReviewInput.dislike());

    // Patch set 1 has 2 votes on Code-Review
    requestScopeOperations.setApiUser(admin.id());
    votes = gApi.changes().id(changeId).current().votes();
    assertThat(votes.keySet()).containsExactly(LabelId.CODE_REVIEW);
    approvals = votes.get(LabelId.CODE_REVIEW);
    assertThat(approvals).hasSize(2);
    assertThat(approvals.stream().map(a -> a._accountId))
        .containsExactlyElementsIn(ImmutableList.of(admin.id().get(), user.id().get()));

    // Create a new patch set which does not have any votes
    amendChange(changeId);
    votes = gApi.changes().id(changeId).current().votes();
    assertThat(votes).isEmpty();

    // Votes are still returned for ps 1
    votes = gApi.changes().id(changeId).revision(1).votes();
    assertThat(votes.keySet()).containsExactly(LabelId.CODE_REVIEW);
    approvals = votes.get(LabelId.CODE_REVIEW);
    assertThat(approvals).hasSize(2);
  }

  @Test
  public void uploaderNotAddedAsReviewer() throws Exception {
    PushOneCommit.Result result = createChange();
    amendChangeWithUploader(result, project, user);
    assertThat(result.getChange().reviewers().all()).isEmpty();
  }

  @Test
  public void notificationsOnPushNewPatchset() throws Exception {
    PushOneCommit.Result r = createChange();
    change(r).addReviewer(user.email());
    sender.clear();

    // check that reviewer is notified.
    amendChange(r.getChangeId());
    ImmutableList<FakeEmailSender.Message> messages = sender.getMessages();
    FakeEmailSender.Message m = Iterables.getOnlyElement(messages);
    assertThat(m.rcpt()).containsExactly(user.getNameEmail());
    assertThat(m.body()).contains("I'd like you to reexamine a change.");
  }

  @Test
  @GerritConfig(name = "change.sendNewPatchsetEmails", value = "false")
  public void notificationsOnPushNewPatchsetNotSentWithSendNewPatchsetEmailsAsFalse()
      throws Exception {
    PushOneCommit.Result r = createChange();
    change(r).addReviewer(user.email());
    sender.clear();

    // check that reviewer is not notified
    amendChange(r.getChangeId());
    assertThat(sender.getMessages()).isEmpty();
  }

  @Test
  @GerritConfig(name = "change.sendNewPatchsetEmails", value = "false")
  public void notificationsOnPushNewPatchsetAlwaysSentToProjectWatchers() throws Exception {
    PushOneCommit.Result r = createChange();
    requestScopeOperations.setApiUser(user.id());
    watch(project.get());
    sender.clear();

    // check that watcher is notified
    amendChange(r.getChangeId());
    ImmutableList<FakeEmailSender.Message> messages = sender.getMessages();
    FakeEmailSender.Message m = Iterables.getOnlyElement(messages);
    assertThat(m.rcpt()).containsExactly(user.getNameEmail());
    assertThat(m.body()).contains(admin.fullName() + " has uploaded a new patch set (#2).");
  }

  @Test
  public void parentData_notPopulatedIfParentsChangeOptionIsNotSet() throws Exception {
    PushOneCommit.Result r = createChange();
    List<ParentInfo> parentsData =
        getRevisionWithoutParents(r.getChangeId(), /* patchSetNumber= */ 1).parentsData;
    assertThat(parentsData).isNull();
  }

  @Test
  public void parentData_parentIsATargetBranch() throws Exception {
    RevCommit initialCommit = getHead(repo(), "HEAD");
    PushOneCommit.Result r = createChange();
    List<ParentInfo> parentsData =
        getRevisionWithParents(r.getChangeId(), /* patchSetNumber= */ 1).parentsData;
    assertThat(parentsData).hasSize(1);
    assertParentIsInTargetBranch(
        parentsData.get(0), "refs/heads/master", initialCommit.getId().name());
  }

  @Test
  public void parentData_parentIsATargetBranch_changeMoved() throws Exception {
    createBranch(BranchNameKey.create(project, "foo"));

    // PS1 was set to be merged in refs/heads/master
    RevCommit initialCommit = getHead(repo(), "HEAD");
    PushOneCommit.Result r = createChange();

    // Create PS2, and set target branch to refs/heads/foo
    amendChange(r.getChangeId());
    move(r.getChangeId(), "foo");

    // PS1 is based on refs/heads/master
    List<ParentInfo> parentsData =
        getRevisionWithParents(r.getChangeId(), /* patchSetNumber= */ 1).parentsData;
    assertThat(parentsData).hasSize(1);
    assertParentIsInTargetBranch(parentsData.get(0), "refs/heads/master", initialCommit.name());

    // PS2 is based on refs/heads/foo
    parentsData = getRevisionWithParents(r.getChangeId(), /* patchSetNumber= */ 2).parentsData;
    assertThat(parentsData).hasSize(1);
    assertParentIsInTargetBranch(parentsData.get(0), "refs/heads/foo", initialCommit.name());
  }

  @Test
  public void parentData_parentIsAPendingChange() throws Exception {
    PushOneCommit.Result r1 = createChange();
    PushOneCommit.Result r2 = createChange();

    List<ParentInfo> parentsData =
        getRevisionWithParents(r2.getChangeId(), /* patchSetNumber= */ 1).parentsData;
    assertThat(parentsData).hasSize(1);
    assertParentIsChange(
        parentsData.get(0),
        r1.getCommit().getId(),
        r1.getChangeId(),
        r1.getChange().change().getChangeId(),
        /* patchSetNumber= */ 1,
        /* parentChangeStatus= */ "NEW",
        /* isParentCommitMergedInTargetBranch= */ false);
  }

  @Test
  public void parentData_parentIsANonLastPatchSet_parentIsPending() throws Exception {
    PushOneCommit.Result r1 = createChange();
    PushOneCommit.Result r2 = createChange();

    // Create PS2 of the first change
    testRepo.reset(r1.getCommit());
    amendChange(r1.getChangeId());
    assertThat(gApi.changes().id(r1.getChangeId()).get().revisions).hasSize(2);

    List<ParentInfo> parentsData =
        getRevisionWithParents(r2.getChangeId(), /* patchSetNumber= */ 1).parentsData;
    assertThat(parentsData).hasSize(1);
    assertParentIsChange(
        parentsData.get(0),
        r1.getCommit().getId(),
        r1.getChangeId(),
        r1.getChange().change().getChangeId(),
        /* patchSetNumber= */ 1,
        /* parentChangeStatus= */ "NEW",
        /* isParentCommitMergedInTargetBranch= */ false);
  }

  @Test
  public void parentData_parentIsALastPatchSet_parentIsPending() throws Exception {
    // Create two patch-sets of change 1, and base change 2 on the second PS of change 1.
    PushOneCommit.Result r1 = createChange();
    PushOneCommit.Result r12 = amendChange(r1.getChangeId());
    assertThat(gApi.changes().id(r1.getChangeId()).get().revisions).hasSize(2);
    PushOneCommit.Result r2 = createChange();

    List<ParentInfo> parentsData =
        getRevisionWithParents(r2.getChangeId(), /* patchSetNumber= */ 1).parentsData;
    assertThat(parentsData).hasSize(1);
    assertParentIsChange(
        parentsData.get(0),
        r12.getCommit().getId(),
        r1.getChangeId(),
        r1.getChange().change().getChangeId(),
        /* patchSetNumber= */ 2,
        /* parentChangeStatus= */ "NEW",
        /* isParentCommitMergedInTargetBranch= */ false);
  }

  @Test
  public void parentData_parentIsANonLastPatchSet_parentIsMerged() throws Exception {
    PushOneCommit.Result r1 = createChange();
    PushOneCommit.Result r2 = createChange();

    // Create PS2 of the first change, and merge it.
    testRepo.reset(r1.getCommit());
    amendChange(r1.getChangeId());
    approve(r1.getChangeId());
    gApi.changes().id(r1.getChangeId()).current().submit();

    List<ParentInfo> parentsData =
        getRevisionWithParents(r2.getChangeId(), /* patchSetNumber= */ 1).parentsData;
    assertThat(parentsData).hasSize(1);
    assertParentIsChange(
        parentsData.get(0),
        r1.getCommit().getId(),
        r1.getChangeId(),
        r1.getChange().change().getChangeId(),
        /* patchSetNumber= */ 1,
        /* parentChangeStatus= */ "MERGED",
        /* isParentCommitMergedInTargetBranch= */ false);
  }

  @Test
  public void parentData_parentIsLastPatchSet_parentIsMerged_fastForwardSubmitStrategy()
      throws Exception {
    updateSubmitType(project, SubmitType.FAST_FORWARD_ONLY);

    PushOneCommit.Result r11 = createChange();
    PushOneCommit.Result r12 = amendChange(r11.getChangeId());
    PushOneCommit.Result r2 = createChange();

    // Merge the first change
    approve(r11.getChangeId());
    gApi.changes().id(r11.getChangeId()).current().submit();

    List<ParentInfo> parentsData =
        getRevisionWithParents(r2.getChangeId(), /* patchSetNumber= */ 1).parentsData;
    assertThat(parentsData).hasSize(1);

    // The second change is based on the last patch-set of the first change. But since the first
    // change is merged and the submit strategy is fast-forward, PS2 of change 1 is now included
    // in the target branch, hence the parent data reflects that.
    assertParentIsInTargetBranch(
        parentsData.get(0), "refs/heads/master", r12.getCommit().getId().name());
    assertParentIsChange(
        parentsData.get(0),
        r12.getCommit().getId(),
        r11.getChangeId(),
        r11.getChange().change().getChangeId(),
        /* patchSetNumber= */ 2,
        /* parentChangeStatus= */ "MERGED",
        /* isParentCommitMergedInTargetBranch= */ true);
  }

  @Test
  public void parentData_parentIsLastPatchSet_parentIsMerged_rebaseAlwaysSubmitStrategy()
      throws Exception {
    updateSubmitType(project, SubmitType.REBASE_ALWAYS);

    PushOneCommit.Result r11 = createChange();
    PushOneCommit.Result r12 = amendChange(r11.getChangeId());
    PushOneCommit.Result r2 = createChange();

    // Merge the first change
    approve(r11.getChangeId());
    gApi.changes().id(r11.getChangeId()).current().submit();

    List<ParentInfo> parentsData =
        getRevisionWithParents(r2.getChangeId(), /* patchSetNumber= */ 1).parentsData;
    assertThat(parentsData).hasSize(1);

    // The second change is based on the last patch-set of the first change. But since the first
    // change is merged and the submit strategy is rebase-always, PS2 of change 1 is not included
    // in the target branch and there is another commit that got rebased in the target branch. The
    // parent of change 2 is patch-set 2 of change 1.
    assertParentIsChange(
        parentsData.get(0),
        r12.getCommit().getId(),
        r11.getChangeId(),
        r11.getChange().change().getChangeId(),
        /* patchSetNumber= */ 2,
        /* parentChangeStatus= */ "MERGED",
        /* isParentCommitMergedInTargetBranch= */ false);
  }

  private RevisionInfo getRevisionWithParents(String changeId, int patchSetNumber)
      throws Exception {
    return getRevision(
        changeId,
        patchSetNumber,
        EnumSet.complementOf(EnumSet.of(ListChangesOption.CHECK, ListChangesOption.SKIP_DIFFSTAT)));
  }

  private RevisionInfo getRevisionWithoutParents(String changeId, int patchSetNumber)
      throws Exception {
    return getRevision(
        changeId,
        patchSetNumber,
        EnumSet.complementOf(
            EnumSet.of(
                ListChangesOption.PARENTS,
                ListChangesOption.CHECK,
                ListChangesOption.SKIP_DIFFSTAT)));
  }

  private RevisionInfo getRevision(
      String changeId, int patchSetNumber, EnumSet<ListChangesOption> options) throws Exception {
    ChangeInfo changeInfo = gApi.changes().id(changeId).get(options);
    return changeInfo.revisions.values().stream()
        .filter(revision -> revision._number == patchSetNumber)
        .findAny()
        .get();
  }

  private void updateSubmitType(Project.NameKey project, SubmitType submitType) throws Exception {
    ConfigInput input = new ConfigInput();
    input.submitType = submitType;
    gApi.projects().name(project.get()).config(input);
  }

  private void assertParentIsInTargetBranch(ParentInfo info, String branchName, String commitId) {
    assertThat(info.branchName).isEqualTo(branchName);
    assertThat(info.commitId).isEqualTo(commitId);
    assertThat(info.isMergedInTargetBranch).isTrue();
  }

  private void assertParentIsChange(
      ParentInfo info,
      ObjectId parentCommitId,
      String changeId,
      Integer changeNumber,
      Integer patchSetNumber,
      String parentChangeStatus,
      boolean isParentCommitMergedInTargetBranch) {
    assertThat(info.commitId).isEqualTo(parentCommitId.name());
    assertThat(info.changeStatus).isEqualTo(parentChangeStatus);
    assertThat(info.changeNumber).isEqualTo(changeNumber);
    assertThat(info.changeId).isEqualTo(changeId);
    assertThat(info.patchSetNumber).isEqualTo(patchSetNumber);
    assertThat(info.isMergedInTargetBranch).isEqualTo(isParentCommitMergedInTargetBranch);
  }

  private static void assertCherryPickResult(
      ChangeInfo changeInfo, CherryPickInput input, String srcChangeId) {
    assertThat(changeInfo.changeId).isEqualTo(srcChangeId);
    assertThat(changeInfo.revisions.keySet()).containsExactly(changeInfo.currentRevision);
    RevisionInfo revisionInfo = changeInfo.revisions.get(changeInfo.currentRevision);
    assertThat(revisionInfo.commit.message).isEqualTo(input.message);
    assertThat(revisionInfo.commit.parents).hasSize(1);
    assertThat(revisionInfo.commit.parents.get(0).commit).isEqualTo(input.base);
  }

  private void move(String changeId, String destination) throws Exception {
    gApi.changes().id(changeId).move(destination);
  }

  private PushOneCommit.Result updateChange(PushOneCommit.Result r, String content)
      throws Exception {
    PushOneCommit push =
        pushFactory.create(
            admin.newIdent(), testRepo, "test commit", "a.txt", content, r.getChangeId());
    return push.to("refs/for/master");
  }

  private RevisionApi current(PushOneCommit.Result r) throws Exception {
    return gApi.changes().id(r.getChangeId()).current();
  }

  private PushOneCommit.Result createCherryPickableMerge(
      String parent1FileName, String parent2FileName) throws Exception {
    RevCommit initialCommit = getHead(repo(), "HEAD");

    String branchAName = "branchA";
    createBranch(BranchNameKey.create(project, branchAName));
    String branchBName = "branchB";
    createBranch(BranchNameKey.create(project, branchBName));

    PushOneCommit.Result changeAResult =
        pushFactory
            .create(admin.newIdent(), testRepo, "change a", parent1FileName, "Content of a")
            .to("refs/for/" + branchAName);

    testRepo.reset(initialCommit);
    PushOneCommit.Result changeBResult =
        pushFactory
            .create(admin.newIdent(), testRepo, "change b", parent2FileName, "Content of b")
            .to("refs/for/" + branchBName);

    PushOneCommit pushableMergeCommit =
        pushFactory.create(
            admin.newIdent(),
            testRepo,
            "merge",
            ImmutableMap.of(parent1FileName, "Content of a", parent2FileName, "Content of b"));
    pushableMergeCommit.setParents(
        ImmutableList.of(changeAResult.getCommit(), changeBResult.getCommit()));
    PushOneCommit.Result mergeChangeResult = pushableMergeCommit.to("refs/for/" + branchAName);
    mergeChangeResult.assertOkStatus();
    return mergeChangeResult;
  }

  private ApprovalInfo getApproval(String changeId, String label) throws Exception {
    ChangeInfo info = gApi.changes().id(changeId).get(DETAILED_LABELS);
    LabelInfo li = info.labels.get(label);
    assertThat(li).isNotNull();
    int accountId = localCtx.getContext().getUser().getAccountId().get();
    return li.all.stream().filter(a -> a._accountId == accountId).findFirst().get();
  }

  private static Iterable<Account.Id> getReviewers(Collection<AccountInfo> r) {
    return Iterables.transform(r, a -> Account.id(a._accountId));
  }

  @Test
  public void getCommitMessageFromOlderPatchSet() throws Exception {
    PushOneCommit.Result result =
        createChange(
            testRepo, "master", "Initial commit message", FILE_NAME, "other content", null);
    Change.Id changeId = result.getChange().getId();
    PatchSet.Id initialPatchSetId = result.getPatchSetId();

    pushFactory
        .create(
            admin.newIdent(),
            testRepo,
            "another commit message",
            FILE_NAME,
            "new content",
            result.getChangeId())
        .to("refs/for/master");

    // Fetch the commit message for the initial patch set using the /content endpoint
    String commitMessage =
        gApi.changes()
            .id(changeId.get())
            .revision(initialPatchSetId.get())
            .file(COMMIT_MSG)
            .content()
            .asString();

    assertThat(commitMessage).startsWith("Initial commit message");
  }
}
