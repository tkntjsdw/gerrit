// Copyright (C) 2014 The Android Open Source Project
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

package com.google.gerrit.acceptance.rest.change;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.TruthJUnit.assume;
import static com.google.gerrit.acceptance.GitUtil.pushHead;
import static com.google.gerrit.acceptance.TestExtensions.TestCommitValidationInfoListener;
import static com.google.gerrit.acceptance.TestExtensions.TestCommitValidationListener;
import static com.google.gerrit.acceptance.TestExtensions.TestValidationOptionsListener;
import static com.google.gerrit.acceptance.testsuite.project.TestProjectUpdate.allow;
import static com.google.gerrit.acceptance.testsuite.project.TestProjectUpdate.block;
import static com.google.gerrit.entities.Permission.CREATE;
import static com.google.gerrit.entities.Permission.READ;
import static com.google.gerrit.entities.RefNames.HEAD;
import static com.google.gerrit.entities.RefNames.changeMetaRef;
import static com.google.gerrit.extensions.client.ListChangesOption.ALL_REVISIONS;
import static com.google.gerrit.extensions.client.ListChangesOption.CURRENT_COMMIT;
import static com.google.gerrit.extensions.client.ListChangesOption.CURRENT_REVISION;
import static com.google.gerrit.extensions.common.testing.GitPersonSubject.assertThat;
import static com.google.gerrit.server.group.SystemGroupBackend.REGISTERED_USERS;
import static com.google.gerrit.testing.GerritJUnit.assertThrows;
import static com.google.gerrit.testing.TestActionRefUpdateContext.testRefAction;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.eclipse.jgit.lib.Constants.SIGNED_OFF_BY_TAG;

import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.gerrit.acceptance.AbstractDaemonTest;
import com.google.gerrit.acceptance.ExtensionRegistry;
import com.google.gerrit.acceptance.ExtensionRegistry.Registration;
import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.PushOneCommit.Result;
import com.google.gerrit.acceptance.RestResponse;
import com.google.gerrit.acceptance.UseClockStep;
import com.google.gerrit.acceptance.UseSystemTime;
import com.google.gerrit.acceptance.config.GerritConfig;
import com.google.gerrit.acceptance.testsuite.project.ProjectOperations;
import com.google.gerrit.acceptance.testsuite.request.RequestScopeOperations;
import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.PatchSet;
import com.google.gerrit.entities.Permission;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.RefNames;
import com.google.gerrit.entities.converter.ChangeInputProtoConverter;
import com.google.gerrit.extensions.api.accounts.AccountInput;
import com.google.gerrit.extensions.api.changes.ApplyPatchInput;
import com.google.gerrit.extensions.api.changes.ChangeApi;
import com.google.gerrit.extensions.api.changes.CherryPickInput;
import com.google.gerrit.extensions.api.changes.NotifyHandling;
import com.google.gerrit.extensions.api.changes.ReviewInput;
import com.google.gerrit.extensions.api.changes.RevisionApi;
import com.google.gerrit.extensions.api.projects.BranchInput;
import com.google.gerrit.extensions.client.ChangeStatus;
import com.google.gerrit.extensions.client.GeneralPreferencesInfo;
import com.google.gerrit.extensions.client.InheritableBoolean;
import com.google.gerrit.extensions.client.ListChangesOption;
import com.google.gerrit.extensions.client.ReviewerState;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.extensions.common.ChangeInput;
import com.google.gerrit.extensions.common.ChangeMessageInfo;
import com.google.gerrit.extensions.common.DiffInfo;
import com.google.gerrit.extensions.common.GitPerson;
import com.google.gerrit.extensions.common.MergeInput;
import com.google.gerrit.extensions.common.NoMergeBaseReason;
import com.google.gerrit.extensions.common.RevisionInfo;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.BinaryResult;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.extensions.restapi.ResourceNotFoundException;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.extensions.restapi.UnprocessableEntityException;
import com.google.gerrit.server.git.validators.CommitValidationInfo;
import com.google.gerrit.server.patch.ApplyPatchUtil;
import com.google.gerrit.server.restapi.change.CreateChange;
import com.google.gerrit.server.restapi.change.CreateChange.CommitTreeSupplier;
import com.google.gerrit.server.submit.ChangeAlreadyMergedException;
import com.google.gerrit.server.update.BatchUpdate;
import com.google.gerrit.testing.FakeEmailSender.Message;
import com.google.gson.stream.JsonReader;
import com.google.inject.Inject;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate.Status;
import org.eclipse.jgit.util.Base64;
import org.junit.Before;
import org.junit.Test;

@UseClockStep
public class CreateChangeIT extends AbstractDaemonTest {
  private static final ChangeInputProtoConverter CHANGE_INPUT_PROTO_CONVERTER =
      ChangeInputProtoConverter.INSTANCE;
  @Inject private ProjectOperations projectOperations;
  @Inject private RequestScopeOperations requestScopeOperations;
  @Inject private ExtensionRegistry extensionRegistry;
  @Inject private CreateChange createChangeImpl;
  @Inject private BatchUpdate.Factory updateFactory;

  @Before
  public void addNonCommitHead() throws Exception {
    testRefAction(
        () -> {
          try (Repository repo = repoManager.openRepository(project);
              ObjectInserter ins = repo.newObjectInserter()) {
            ObjectId answer = ins.insert(Constants.OBJ_BLOB, new byte[] {42});
            ins.flush();
            ins.close();

            RefUpdate update = repo.getRefDatabase().newUpdate("refs/heads/answer", false);
            update.setNewObjectId(answer);
            assertThat(update.forceUpdate()).isEqualTo(RefUpdate.Result.NEW);
          }
        });
  }

  @Test
  public void createEmptyChange_MissingBranch() throws Exception {
    ChangeInput ci = new ChangeInput();
    ci.project = project.get();
    assertCreateFails(ci, BadRequestException.class, "branch must be non-empty");
  }

  @Test
  public void createEmptyChange_NonExistingBranch() throws Exception {
    ChangeInput ci = newChangeInput(ChangeStatus.NEW);
    ci.branch = "non-existing";
    assertCreateFails(ci, BadRequestException.class, "Destination branch does not exist");
  }

  @Test
  public void createEmptyChange_MissingMessage() throws Exception {
    ChangeInput ci = new ChangeInput();
    ci.project = project.get();
    ci.branch = "master";
    assertCreateFails(ci, BadRequestException.class, "commit message must be non-empty");
  }

  @Test
  public void createEmptyChange_InvalidStatus() throws Exception {
    ChangeInput ci = newChangeInput(ChangeStatus.MERGED);
    assertCreateFails(ci, BadRequestException.class, "unsupported change status");
  }

  @Test
  public void createEmptyChange_InvalidChangeId() throws Exception {
    ChangeInput ci = newChangeInput(ChangeStatus.NEW);
    ci.subject = "Subject\n\nChange-Id: I0000000000000000000000000000000000000000";
    assertCreateFails(
        ci, ResourceConflictException.class, "invalid Change-Id line format in message footer");
  }

  @Test
  public void createEmptyChange_InvalidSubject() throws Exception {
    ChangeInput ci = newChangeInput(ChangeStatus.NEW);
    ci.subject = "Change-Id: I1234000000000000000000000000000000000000";
    assertCreateFails(
        ci,
        ResourceConflictException.class,
        "missing subject; Change-Id must be in message footer");
  }

  @Test
  public void createNewChange_InvalidCommentInCommitMessage() throws Exception {
    ChangeInput ci = newChangeInput(ChangeStatus.NEW);
    ci.subject = "#12345 Test";
    assertCreateFails(ci, BadRequestException.class, "commit message must be non-empty");
  }

  @Test
  public void createNewChange_RequiresAuthentication() throws Exception {
    requestScopeOperations.setApiUserAnonymous();
    assertCreateFails(
        newChangeInput(ChangeStatus.NEW), AuthException.class, "Authentication required");
  }

  @Test
  @GerritConfig(name = "change.topicLimit", value = "3")
  public void createNewChange_exceedsTopicLimit() throws Exception {
    assertCreateSucceeds(newChangeWithTopic("limited"));
    assertCreateSucceeds(newChangeWithTopic("limited"));
    assertCreateSucceeds(newChangeWithTopic("limited"));
    ChangeInput ci = newChangeWithTopic("limited");
    assertCreateFails(ci, BadRequestException.class, "topicLimit");
  }

  @Test
  public void createNewChange() throws Exception {
    ChangeInfo info = assertCreateSucceeds(newChangeInput(ChangeStatus.NEW));
    assertThat(info.revisions.get(info.currentRevision).commit.message)
        .contains("Change-Id: " + info.changeId);

    // Verify the message that has been posted on the change.
    List<ChangeMessageInfo> messages = gApi.changes().id(info._number).messages();
    assertThat(messages).hasSize(1);
    assertThat(Iterables.getOnlyElement(messages).message).isEqualTo("Uploaded patch set 1.");

    RevisionInfo currentRevision =
        gApi.changes().id(info.id).get(CURRENT_REVISION, CURRENT_COMMIT).getCurrentRevision();
    assertThat(currentRevision.conflicts).isNotNull();
    assertThat(currentRevision.conflicts.containsConflicts).isFalse();
    assertThat(currentRevision.conflicts.base).isNull();
    assertThat(currentRevision.conflicts.ours).isNull();
    assertThat(currentRevision.conflicts.theirs).isNull();
    assertThat(currentRevision.conflicts.mergeStrategy).isNull();
    assertThat(currentRevision.conflicts.noBaseReason)
        .isEqualTo(NoMergeBaseReason.NO_MERGE_PERFORMED);
  }

  @Test
  public void createNewChangeWithCommentsInCommitMessage() throws Exception {
    ChangeInput ci = newChangeInput(ChangeStatus.NEW);
    ci.subject += "\n# Comment line";
    ChangeInfo info = gApi.changes().create(ci).get();
    assertThat(info.revisions.get(info.currentRevision).commit.message)
        .doesNotContain("# Comment line");
  }

  @Test
  public void createNewChangeWithChangeId() throws Exception {
    ChangeInput ci = newChangeInput(ChangeStatus.NEW);
    String changeId = "I1234000000000000000000000000000000000000";
    String changeIdLine = "Change-Id: " + changeId;
    ci.subject = "Subject\n\n" + changeIdLine;
    ChangeInfo info = assertCreateSucceeds(ci);
    assertThat(info.changeId).isEqualTo(changeId);
    assertThat(info.revisions.get(info.currentRevision).commit.message).contains(changeIdLine);
  }

  @Test
  public void formatResponse_fieldsPresentWhenRequested() throws Exception {
    ChangeInput ci = newChangeInput(ChangeStatus.NEW);
    String changeId = "I1234000000000000000000000000000000000000";
    String changeIdLine = "Change-Id: " + changeId;
    ci.subject = "Subject\n\n" + changeIdLine;
    ci.responseFormatOptions =
        ImmutableList.of(ListChangesOption.CURRENT_REVISION, ListChangesOption.CURRENT_ACTIONS);
    // Must use REST directly because the Java API returns a ChangeApi upon
    // creation that will do its own formatting when #get is called on it.
    RestResponse resp = adminRestSession.post("/changes/", ci);
    resp.assertCreated();
    ChangeInfo res = readContentFromJson(resp, ChangeInfo.class);
    assertThat(res.actions).isNotEmpty();
    assertThat(res.revisions.values()).hasSize(1);
  }

  @Test
  public void formatResponse_fieldsAbsentWhenNotRequested() throws Exception {
    ChangeInput ci = newChangeInput(ChangeStatus.NEW);
    String changeId = "I1234000000000000000000000000000000000000";
    String changeIdLine = "Change-Id: " + changeId;
    ci.subject = "Subject\n\n" + changeIdLine;
    // Must use REST directly because the Java API returns a ChangeApi upon
    // creation that will do its own formatting when #get is called on it.
    RestResponse resp = adminRestSession.post("/changes/", ci);
    resp.assertCreated();
    ChangeInfo res = readContentFromJson(resp, ChangeInfo.class);
    assertThat(res.actions).isNull();
    assertThat(res.revisions).isNull();
  }

  @Test
  public void cannotCreateChangeOnGerritInternalRefs() throws Exception {
    requestScopeOperations.setApiUser(admin.id());
    projectOperations
        .project(project)
        .forUpdate()
        .add(allow(CREATE).ref("refs/*").group(REGISTERED_USERS))
        .update();

    requestScopeOperations.setApiUser(user.id());
    ChangeInput ci = newChangeInput(ChangeStatus.NEW);
    ci.subject = "Subject";
    ci.branch = "refs/changes/00/1000"; // disallowedRef

    Throwable thrown = assertThrows(RestApiException.class, () -> gApi.changes().create(ci));
    assertThat(thrown).hasMessageThat().contains("Cannot create a change on ref " + ci.branch);
  }

  @Test
  public void cannotCreateChangeOnTagRefs() throws Exception {
    requestScopeOperations.setApiUser(admin.id());
    projectOperations
        .project(project)
        .forUpdate()
        .add(allow(CREATE).ref("refs/*").group(REGISTERED_USERS))
        .update();

    requestScopeOperations.setApiUser(user.id());
    ChangeInput ci = newChangeInput(ChangeStatus.NEW);
    ci.subject = "Subject";
    ci.branch = "refs/tags/v1.0"; // disallowed ref

    Throwable thrown = assertThrows(RestApiException.class, () -> gApi.changes().create(ci));
    assertThat(thrown).hasMessageThat().contains("Cannot create a change on ref " + ci.branch);
  }

  @Test
  public void canCreateChangeOnRefsMetaConfig() throws Exception {
    requestScopeOperations.setApiUser(admin.id());
    projectOperations
        .project(project)
        .forUpdate()
        .add(allow(CREATE).ref("refs/*").group(REGISTERED_USERS))
        .add(allow(READ).ref("refs/meta/config").group(REGISTERED_USERS))
        .update();
    requestScopeOperations.setApiUser(user.id());
    ChangeInput ci = newChangeInput(ChangeStatus.NEW);
    ci.subject = "Subject";
    ci.branch = RefNames.REFS_CONFIG;
    assertThat(gApi.changes().create(ci).info().branch).isEqualTo(RefNames.REFS_CONFIG);
  }

  @Test
  public void canCreateChangeOnRefsMetaDashboards() throws Exception {
    String branchName = "refs/meta/dashboards/project_1";
    requestScopeOperations.setApiUser(admin.id());
    projectOperations
        .project(project)
        .forUpdate()
        .add(allow(CREATE).ref(branchName).group(REGISTERED_USERS))
        .add(allow(READ).ref(branchName).group(REGISTERED_USERS))
        .update();
    BranchNameKey branchNameKey = BranchNameKey.create(project, branchName);
    createBranch(branchNameKey);
    requestScopeOperations.setApiUser(user.id());
    ChangeInput ci = newChangeInput(ChangeStatus.NEW);
    ci.subject = "Subject";
    ci.branch = branchName;
    assertThat(gApi.changes().create(ci).info().branch).isEqualTo(branchName);
  }

  @Test
  public void cannotCreateChangeWithChangeIfOfExistingChangeOnSameBranch() throws Exception {
    String changeId = createChange().getChangeId();

    ChangeInput ci = newChangeInput(ChangeStatus.NEW);
    ci.subject = "Subject\n\nChange-Id: " + changeId;
    assertCreateFails(
        ci,
        ResourceConflictException.class,
        "A change with Change-Id " + changeId + " already exists for this branch.");
  }

  @Test
  public void canCreateChangeWithChangeIfOfExistingChangeOnOtherBranch() throws Exception {
    String changeId = createChange().getChangeId();

    createBranch(BranchNameKey.create(project, "other"));

    ChangeInput ci = newChangeInput(ChangeStatus.NEW);
    ci.subject = "Subject\n\nChange-Id: " + changeId;
    ci.branch = "other";
    ChangeInfo info = assertCreateSucceeds(ci);
    assertThat(info.changeId).isEqualTo(changeId);
  }

  @Test
  public void notificationsOnChangeCreation() throws Exception {
    requestScopeOperations.setApiUser(user.id());
    watch(project.get());

    // check that watcher is notified
    requestScopeOperations.setApiUser(admin.id());
    assertCreateSucceeds(newChangeInput(ChangeStatus.NEW));

    ImmutableList<Message> messages = sender.getMessages();
    assertThat(messages).hasSize(1);
    Message m = messages.get(0);
    assertThat(m.rcpt()).containsExactly(user.getNameEmail());
    assertThat(m.body()).contains(admin.fullName() + " has uploaded this change for review.");

    // check that watcher is not notified if notify=NONE
    sender.clear();
    ChangeInput input = newChangeInput(ChangeStatus.NEW);
    input.notify = NotifyHandling.NONE;
    assertCreateSucceeds(input);
    assertThat(sender.getMessages()).isEmpty();
  }

  @Test
  public void createNewChangeSignedOffByFooter() throws Exception {
    setSignedOffByFooter(true);
    try {
      ChangeInfo info = assertCreateSucceeds(newChangeInput(ChangeStatus.NEW));
      String message = info.revisions.get(info.currentRevision).commit.message;
      assertThat(message)
          .contains(
              String.format(
                  "%sAdministrator <%s>", SIGNED_OFF_BY_TAG, admin.newIdent().getEmailAddress()));
    } finally {
      setSignedOffByFooter(false);
    }
  }

  @Test
  public void createNewChange_projectConfigRequiresSignedOffByFooter() throws Exception {
    projectOperations.project(project).forUpdate().useSignedOffBy().update();

    ChangeInfo info = assertCreateSucceeds(newChangeInput(ChangeStatus.NEW));
    String message = info.revisions.get(info.currentRevision).commit.message;
    assertThat(message)
        .contains(
            String.format(
                "%sAdministrator <%s>", SIGNED_OFF_BY_TAG, admin.newIdent().getEmailAddress()));
  }

  @Test
  public void createNewChange_projectConfigDoesNotRequireSignedOffByFooter() throws Exception {
    projectOperations
        .project(project)
        .forUpdate()
        .useSignedOffBy(InheritableBoolean.FALSE)
        .update();

    ChangeInfo info = assertCreateSucceeds(newChangeInput(ChangeStatus.NEW));
    String message = info.revisions.get(info.currentRevision).commit.message;
    assertThat(message).doesNotContain(SIGNED_OFF_BY_TAG);
  }

  @Test
  public void createNewChangeSignedOffByFooterWithChangeId() throws Exception {
    setSignedOffByFooter(true);
    try {
      ChangeInput ci = newChangeInput(ChangeStatus.NEW);
      String changeId = "I1234000000000000000000000000000000000000";
      String changeIdLine = "Change-Id: " + changeId;
      ci.subject = "Subject\n\n" + changeIdLine;
      ChangeInfo info = assertCreateSucceeds(ci);
      assertThat(info.changeId).isEqualTo(changeId);
      String message = info.revisions.get(info.currentRevision).commit.message;
      assertThat(message).contains(changeIdLine);
      assertThat(message)
          .contains(
              String.format(
                  "%sAdministrator <%s>", SIGNED_OFF_BY_TAG, admin.newIdent().getEmailAddress()));
    } finally {
      setSignedOffByFooter(false);
    }
  }

  @Test
  public void createNewPrivateChange() throws Exception {
    ChangeInput input = newChangeInput(ChangeStatus.NEW);
    input.isPrivate = true;
    assertCreateSucceeds(input);
  }

  @Test
  public void createDefaultAuthor() throws Exception {
    ChangeInput input = newChangeInput(ChangeStatus.NEW);
    ChangeInfo info = assertCreateSucceeds(input);
    GitPerson person = gApi.changes().id(info.id).current().commit(false).author;
    assertThat(person).email().isEqualTo(admin.email());
  }

  @Test
  public void createAuthorOverrideBadRequest() throws Exception {
    ChangeInput input = newChangeInput(ChangeStatus.NEW);
    input.author = new AccountInput();
    input.author.name = "name";
    assertCreateFails(input, BadRequestException.class, "email");
    input.author.name = null;
    input.author.email = "gerritlessjane@invalid";
    assertCreateFails(input, BadRequestException.class, "email");
  }

  @Test
  public void createAuthorOverride() throws Exception {
    ChangeInput input = newChangeInput(ChangeStatus.NEW);
    input.author = new AccountInput();
    input.author.email = "gerritlessjane@invalid";
    // This is an email address that doesn't exist as account on the Gerrit server.
    input.author.name = "Gerritless Jane";
    ChangeInfo info = assertCreateSucceeds(input);

    RevisionApi rApi = gApi.changes().id(info.id).current();
    GitPerson author = rApi.commit(false).author;
    assertThat(author).email().isEqualTo(input.author.email);
    assertThat(author).name().isEqualTo(input.author.name);
    GitPerson committer = rApi.commit(false).committer;
    assertThat(committer).email().isEqualTo(admin.getNameEmail().email());
  }

  @Test
  public void createAuthorPermission() throws Exception {
    ChangeInput input = newChangeInput(ChangeStatus.NEW);
    input.author = new AccountInput();
    input.author.name = "Jane";
    input.author.email = "jane@invalid";
    projectOperations
        .project(project)
        .forUpdate()
        .add(block(Permission.FORGE_AUTHOR).ref("refs/*").group(REGISTERED_USERS))
        .update();
    assertCreateFails(input, AuthException.class, "forge author");
  }

  @Test
  public void createAuthorAddedAsCcAndNotified() throws Exception {
    ChangeInput input = newChangeInput(ChangeStatus.NEW);
    input.author = new AccountInput();
    input.author.email = user.email();
    input.author.name = user.fullName();

    ChangeInfo info = assertCreateSucceeds(input);
    assertThat(info.reviewers.get(ReviewerState.CC)).hasSize(1);
    assertThat(Iterables.getOnlyElement(info.reviewers.get(ReviewerState.CC)).email)
        .isEqualTo(user.email());
    assertThat(
            Iterables.getOnlyElement(Iterables.getOnlyElement(sender.getMessages()).rcpt()).email())
        .isEqualTo(user.email());
  }

  @Test
  public void createAuthorAddedAsCcNotNotifiedWithNotifyNone() throws Exception {
    ChangeInput input = newChangeInput(ChangeStatus.NEW);
    input.author = new AccountInput();
    input.author.email = user.email();
    input.author.name = user.fullName();
    input.notify = NotifyHandling.NONE;

    ChangeInfo info = assertCreateSucceeds(input);
    assertThat(info.reviewers.get(ReviewerState.CC)).hasSize(1);
    assertThat(Iterables.getOnlyElement(info.reviewers.get(ReviewerState.CC)).email)
        .isEqualTo(user.email());
    assertThat(sender.getMessages()).isEmpty();
  }

  @Test
  public void createWithMergeConflictAuthorAddedAsCcNotNotifiedWithNotifyNone() throws Exception {
    String fileName = "shared.txt";
    String sourceBranch = "sourceBranch";
    String sourceSubject = "source change";
    String sourceContent = "source content";
    String targetBranch = "targetBranch";
    String targetSubject = "target change";
    String targetContent = "target content";
    changeInTwoBranches(
        sourceBranch,
        sourceSubject,
        fileName,
        sourceContent,
        targetBranch,
        targetSubject,
        fileName,
        targetContent);
    ChangeInput input =
        newMergeChangeInput(targetBranch, sourceBranch, "", /* allowConflicts= */ true);
    input.workInProgress = true;
    input.author = new AccountInput();
    input.author.email = user.email();
    input.author.name = user.fullName();
    input.notify = NotifyHandling.NONE;
    ChangeInfo info = assertCreateSucceeds(input);

    assertThat(info.reviewers.get(ReviewerState.CC)).hasSize(1);
    assertThat(Iterables.getOnlyElement(info.reviewers.get(ReviewerState.CC)).email)
        .isEqualTo(user.email());
    assertThat(sender.getMessages()).isEmpty();
  }

  @Test
  public void createAuthorNotAddedAsCcWithAvoidAddingOriginalAuthorAsReviewer() throws Exception {
    projectOperations
        .project(project)
        .forUpdate()
        .skipAddingAuthorAndCommitterAsReviewers()
        .update();
    ChangeInput input = newChangeInput(ChangeStatus.NEW);
    input.author = new AccountInput();
    input.author.email = user.email();
    input.author.name = user.fullName();

    ChangeInfo info = assertCreateSucceeds(input);
    assertThat(info.reviewers).isEmpty();
  }

  @Test
  public void createNewWorkInProgressChange() throws Exception {
    ChangeInput input = newChangeInput(ChangeStatus.NEW);
    input.workInProgress = true;
    assertCreateSucceeds(input);
  }

  @Test
  public void createChangeWithParentCommit() throws Exception {
    ImmutableMap<String, PushOneCommit.Result> setup =
        changeInTwoBranches("foo", "foo.txt", "bar", "bar.txt");
    ChangeInput input = newChangeInput(ChangeStatus.NEW);
    input.baseCommit = setup.get("master").getCommit().getId().name();
    ChangeInfo result = assertCreateSucceeds(input);
    assertThat(gApi.changes().id(result.id).current().commit(false).parents.get(0).commit)
        .isEqualTo(input.baseCommit);
  }

  @Test
  public void createChangeWithParentChange() throws Exception {
    Result change = createChange();
    ChangeInput input = newChangeInput(ChangeStatus.NEW);
    input.baseChange = change.getChangeId();
    ChangeInfo result = assertCreateSucceeds(input);
    assertThat(gApi.changes().id(result.id).current().commit(false).parents.get(0).commit)
        .isEqualTo(change.getCommit().getId().name());
  }

  @Test
  public void createChangeWithBadParentCommitFails() throws Exception {
    ChangeInput input = newChangeInput(ChangeStatus.NEW);
    input.baseCommit = "notasha1";
    assertCreateFails(
        input, UnprocessableEntityException.class, "Base notasha1 doesn't represent a valid SHA-1");
  }

  @Test
  public void createChangeWithNonExistingParentCommitFails() throws Exception {
    ChangeInput input = newChangeInput(ChangeStatus.NEW);
    input.baseCommit = "deadbeefdeadbeefdeadbeefdeadbeefdeadbeef";
    assertCreateFails(
        input,
        UnprocessableEntityException.class,
        String.format("Base %s doesn't exist", input.baseCommit));
  }

  @Test
  public void createChangeWithParentCommitOnWrongBranchFails() throws Exception {
    ImmutableMap<String, PushOneCommit.Result> setup =
        changeInTwoBranches("foo", "foo.txt", "bar", "bar.txt");
    ChangeInput input = newChangeInput(ChangeStatus.NEW);
    input.branch = "foo";
    input.baseCommit = setup.get("bar").getCommit().getId().name();
    assertCreateFails(
        input,
        BadRequestException.class,
        String.format("Commit %s doesn't exist on ref refs/heads/foo", input.baseCommit));
  }

  @Test
  public void createChangeWithParentCommitWithNonExistingTargetBranch() throws Exception {
    Result initialCommit =
        pushFactory
            .create(user.newIdent(), testRepo, "initial commit", "readme.txt", "initial commit")
            .to("refs/heads/master");
    initialCommit.assertOkStatus();

    ChangeInput input = newChangeInput(ChangeStatus.NEW);
    input.branch = "non-existing";
    input.baseCommit = initialCommit.getCommit().getName();
    assertCreateFails(input, BadRequestException.class, "Destination branch does not exist");
  }

  @Test
  public void createChangeOnNonExistingBaseChangeFails() throws Exception {
    ChangeInput input = newChangeInput(ChangeStatus.NEW);
    input.baseChange = "999999";
    assertCreateFails(
        input, UnprocessableEntityException.class, "Base change not found: " + input.baseChange);
  }

  @Test
  public void createChangeWithoutAccessToParentCommitFails() throws Exception {
    ImmutableMap<String, PushOneCommit.Result> results =
        changeInTwoBranches("invisible-branch", "a.txt", "visible-branch", "b.txt");
    projectOperations
        .project(project)
        .forUpdate()
        .add(block(READ).ref("refs/heads/invisible-branch").group(REGISTERED_USERS))
        .update();

    ChangeInput in = newChangeInput(ChangeStatus.NEW);
    in.branch = "visible-branch";
    in.baseChange = results.get("invisible-branch").getChangeId();
    assertCreateFails(
        in, UnprocessableEntityException.class, "Base change not found: " + in.baseChange);
  }

  @Test
  public void noteDbCommit() throws Exception {
    ChangeInfo c = assertCreateSucceeds(newChangeInput(ChangeStatus.NEW));
    try (Repository repo = repoManager.openRepository(project);
        RevWalk rw = new RevWalk(repo)) {
      RevCommit commit =
          rw.parseCommit(repo.exactRef(changeMetaRef(Change.id(c._number))).getObjectId());

      assertThat(commit.getShortMessage()).isEqualTo("Create change");

      PersonIdent expectedAuthor =
          changeNoteUtil.newAccountIdIdent(
              getAccount(admin.id()).id(), c.created.toInstant(), serverIdent.get());
      assertThat(commit.getAuthorIdent()).isEqualTo(expectedAuthor);

      assertThat(commit.getCommitterIdent())
          .isEqualTo(new PersonIdent(serverIdent.get(), c.created));
      assertThat(commit.getParentCount()).isEqualTo(0);
    }
  }

  @Test
  public void createMergeChangeNoConflictsUsingResolveStrategy() throws Exception {
    testCreateMergeChangeNoConflicts("resolve");
  }

  @Test
  public void createMergeChangeNoConflictsUsingRecursiveStrategy() throws Exception {
    testCreateMergeChangeNoConflicts("recursive");
  }

  @Test
  public void createMergeChangeNoConflictsUsingSimpleTwoWayInCoreStrategy() throws Exception {
    testCreateMergeChangeNoConflicts("simple-two-way-in-core");
  }

  @Test
  public void createMergeChangeNoConflictsUsingOursStrategy() throws Exception {
    testCreateMergeChangeNoConflicts("ours");
  }

  @Test
  public void createMergeChangeNoConflictsUsingTheirsStrategy() throws Exception {
    testCreateMergeChangeNoConflicts("theirs");
  }

  public void testCreateMergeChangeNoConflicts(String mergeStrategy) throws Exception {
    String sourceBranch = "sourceBranch";
    String targetBranch = "targetBranch";
    ImmutableMap<String, Result> results =
        changeInTwoBranches(sourceBranch, "a.txt", targetBranch, "b.txt");
    RevCommit baseCommit = results.get("master").getCommit();
    ChangeInput in = newMergeChangeInput(targetBranch, sourceBranch, mergeStrategy);
    ChangeInfo change = assertCreateSucceeds(in);

    // Verify the message that has been posted on the change.
    List<ChangeMessageInfo> messages = gApi.changes().id(project.get(), change._number).messages();
    assertThat(messages).hasSize(1);
    assertThat(Iterables.getOnlyElement(messages).message).isEqualTo("Uploaded patch set 1.");

    // Verify the conflicts information
    RevisionInfo currentRevision =
        gApi.changes().id(change.id).get(CURRENT_REVISION, CURRENT_COMMIT).getCurrentRevision();
    assertThat(currentRevision.commit.parents.get(0).commit)
        .isEqualTo(results.get(targetBranch).getCommit().name());
    assertThat(currentRevision.conflicts).isNotNull();
    assertThat(currentRevision.conflicts.ours)
        .isEqualTo(results.get(targetBranch).getCommit().name());
    assertThat(currentRevision.conflicts.theirs)
        .isEqualTo(results.get(sourceBranch).getCommit().name());
    assertThat(currentRevision.conflicts.mergeStrategy).isEqualTo(mergeStrategy);
    assertThat(currentRevision.conflicts.containsConflicts).isFalse();

    if ("ours".equals(mergeStrategy) || "theirs".equals(mergeStrategy)) {
      assertThat(currentRevision.conflicts.base).isNull();
      assertThat(currentRevision.conflicts.noBaseReason)
          .isEqualTo(NoMergeBaseReason.ONE_SIDED_MERGE_STRATEGY);
    } else {
      assertThat(currentRevision.conflicts.base).isEqualTo(baseCommit.name());
      assertThat(currentRevision.conflicts.noBaseReason).isNull();
    }
  }

  @Test
  public void createMergeChangeAuthor() throws Exception {
    changeInTwoBranches("branchA", "a.txt", "branchB", "b.txt");
    ChangeInput in = newMergeChangeInput("branchA", "branchB", "");
    in.author = new AccountInput();
    in.author.name = "Gerritless Jane";
    in.author.email = "gerritlessjane@invalid";
    ChangeInfo change = assertCreateSucceeds(in);

    RevisionApi rApi = gApi.changes().id(change.id).current();
    GitPerson author = rApi.commit(false).author;
    assertThat(author).email().isEqualTo(in.author.email);
    GitPerson committer = rApi.commit(false).committer;
    assertThat(committer).email().isEqualTo(admin.getNameEmail().email());
  }

  @Test
  public void createMergeChangeFailsDueToConflictsUsingResolveStrategy() throws Exception {
    testCreateMergeChangeFailsDueToConflicts("resolve");
  }

  @Test
  public void createMergeChangeFailsDueToConflictsUsingRecursiveStrategy() throws Exception {
    testCreateMergeChangeFailsDueToConflicts("recursive");
  }

  @Test
  public void createMergeChangeFailsDueToConflictsUsingSimpleTwoWayInCoreStrategy()
      throws Exception {
    testCreateMergeChangeFailsDueToConflicts("simple-two-way-in-core");
  }

  private void testCreateMergeChangeFailsDueToConflicts(String mergeStrategy) throws Exception {
    String fileName = "shared.txt";
    changeInTwoBranches("branchA", fileName, "branchB", fileName);
    ChangeInput in = newMergeChangeInput("branchA", "branchB", mergeStrategy);
    assertCreateFails(
        in,
        RestApiException.class,
        "simple-two-way-in-core".equals(mergeStrategy)
            ? "merge conflict(s)"
            : String.format(
                """
                merge conflict(s):
                * %s
                """,
                fileName));
  }

  @Test
  public void createMergeChangeSucceedsWithConflictsUsingOursStrategy() throws Exception {
    testCreateMergeChangeSucceedsWithConflicts("ours");
  }

  @Test
  public void createMergeChangeSucceedsWithConflictsUsingTheirsStrategy() throws Exception {
    testCreateMergeChangeSucceedsWithConflicts("theirs");
  }

  private void testCreateMergeChangeSucceedsWithConflicts(String mergeStrategy) throws Exception {
    String sourceBranch = "sourceBranch";
    String targetBranch = "targetBranch";
    ImmutableMap<String, Result> results =
        changeInTwoBranches(sourceBranch, "shared.txt", targetBranch, "shared.txt");
    ChangeInput in = newMergeChangeInput(targetBranch, sourceBranch, mergeStrategy);
    ChangeInfo change = assertCreateSucceeds(in);

    // Verify the conflicts information
    RevisionInfo currentRevision =
        gApi.changes().id(change.id).get(CURRENT_REVISION, CURRENT_COMMIT).getCurrentRevision();
    assertThat(currentRevision.commit.parents.get(0).commit)
        .isEqualTo(results.get(targetBranch).getCommit().name());
    assertThat(currentRevision.conflicts).isNotNull();
    assertThat(currentRevision.conflicts.base).isNull();
    assertThat(currentRevision.conflicts.ours)
        .isEqualTo(results.get(targetBranch).getCommit().name());
    assertThat(currentRevision.conflicts.theirs)
        .isEqualTo(results.get(sourceBranch).getCommit().name());
    assertThat(currentRevision.conflicts.mergeStrategy).isEqualTo(mergeStrategy);
    assertThat(currentRevision.conflicts.noBaseReason)
        .isEqualTo(NoMergeBaseReason.ONE_SIDED_MERGE_STRATEGY);
    assertThat(currentRevision.conflicts.containsConflicts).isFalse();
  }

  @Test
  public void createMergeChangeWithConflictsAllowedUsingRecursiveStrategy() throws Exception {
    testCreateMergeChangeConflictsAllowed(/* strategy= */ "recursive", /* useDiff3= */ false);
  }

  @Test
  @GerritConfig(name = "change.diff3ConflictView", value = "true")
  public void createMergeChangeWithConflictsAllowedUsingRecursivetrategyAndDiff3()
      throws Exception {
    testCreateMergeChangeConflictsAllowed(/* strategy= */ "recursive", /* useDiff3= */ true);
  }

  @Test
  public void createMergeChangeWithConflictsAllowedUsingResolveStrategy() throws Exception {
    testCreateMergeChangeConflictsAllowed(/* strategy= */ "resolve", /* useDiff3= */ false);
  }

  @Test
  @GerritConfig(name = "change.diff3ConflictView", value = "true")
  public void createMergeChangeWithConflictsAllowedUsingResolveStrategyAndDiff3() throws Exception {
    testCreateMergeChangeConflictsAllowed(/* strategy= */ "resolve", /* useDiff3= */ true);
  }

  @Test
  public void createMergeChangeWithConflictsAllowedUsingSimpleTwoWayInCoreStrategy()
      throws Exception {
    testCreateMergeChangeConflictsAllowed(
        /* strategy= */ "simple-two-way-in-core", /* useDiff3= */ false);
  }

  @Test
  @GerritConfig(name = "change.diff3ConflictView", value = "true")
  public void createMergeChangeWithConflictsAllowedUsingSimpleTwoWayInCoreStrategyAndDiff3()
      throws Exception {
    testCreateMergeChangeConflictsAllowed(
        /* strategy= */ "simple-two-way-in-core", /* useDiff3= */ true);
  }

  @Test
  public void createMergeChangeWithConflictsAllowedUsingOursStrategy() throws Exception {
    testCreateMergeChangeConflictsAllowed(/* strategy= */ "ours", /* useDiff3= */ false);
  }

  @Test
  @GerritConfig(name = "change.diff3ConflictView", value = "true")
  public void createMergeChangeWithConflictsAllowedUsingOursStrategyAndDiff3() throws Exception {
    testCreateMergeChangeConflictsAllowed(/* strategy= */ "ours", /* useDiff3= */ true);
  }

  @Test
  public void createMergeChangeWithConflictsAllowedUsingTheirsStrategy() throws Exception {
    testCreateMergeChangeConflictsAllowed(/* strategy= */ "theirs", /* useDiff3= */ false);
  }

  @Test
  @GerritConfig(name = "change.diff3ConflictView", value = "true")
  public void createMergeChangeWithConflictsAllowedUsingTheirsStrategyAndDiff3() throws Exception {
    testCreateMergeChangeConflictsAllowed(/* strategy= */ "theirs", /* useDiff3= */ true);
  }

  private void testCreateMergeChangeConflictsAllowed(String strategy, boolean useDiff3)
      throws Exception {
    String fileName = "shared.txt";
    String sourceBranch = "sourceBranch";
    String sourceSubject = "source change";
    String sourceContent = "source content";
    String targetBranch = "targetBranch";
    String targetSubject = "target change";
    String targetContent = "target content";

    ImmutableMap<String, Result> results =
        changeInTwoBranches(
            sourceBranch,
            sourceSubject,
            fileName,
            sourceContent,
            targetBranch,
            targetSubject,
            fileName,
            targetContent);
    RevCommit baseCommit = results.get("master").getCommit();
    ChangeInput in =
        newMergeChangeInput(targetBranch, sourceBranch, strategy, /* allowConflicts= */ true);

    if ("ours".equals(strategy) || "theirs".equals(strategy)) {
      ChangeInfo change = assertCreateSucceeds(in);

      // Verify the conflicts information
      RevisionInfo currentRevision =
          gApi.changes().id(change.id).get(CURRENT_REVISION, CURRENT_COMMIT).getCurrentRevision();
      assertThat(currentRevision.commit.parents.get(0).commit)
          .isEqualTo(results.get(targetBranch).getCommit().name());
      assertThat(currentRevision.conflicts).isNotNull();
      assertThat(currentRevision.conflicts.base).isNull();
      assertThat(currentRevision.conflicts.ours)
          .isEqualTo(results.get(targetBranch).getCommit().name());
      assertThat(currentRevision.conflicts.theirs)
          .isEqualTo(results.get(sourceBranch).getCommit().name());
      assertThat(currentRevision.conflicts.mergeStrategy).isEqualTo(strategy);
      assertThat(currentRevision.conflicts.noBaseReason)
          .isEqualTo(NoMergeBaseReason.ONE_SIDED_MERGE_STRATEGY);
      assertThat(currentRevision.conflicts.containsConflicts).isFalse();

      // Verify that the file content in the created change is correct.
      // We expect that it has conflict markers to indicate the conflict.
      BinaryResult bin =
          gApi.changes().id(project.get(), change._number).current().file(fileName).content();
      ByteArrayOutputStream os = new ByteArrayOutputStream();
      bin.writeTo(os);
      String fileContent = new String(os.toByteArray(), UTF_8);
      assertThat(fileContent).isEqualTo("ours".equals(strategy) ? targetContent : sourceContent);

      return;
    }

    if ("simple-two-way-in-core".equals(strategy)) {
      assertCreateFails(
          in,
          BadRequestException.class,
          "merge with conflicts is not supported with merge strategy: simple-two-way-in-core");
      return;
    }

    ChangeInfo change = assertCreateSucceedsWithConflicts(in);

    // Verify the conflicts information
    RevisionInfo currentRevision =
        gApi.changes().id(change.id).get(CURRENT_REVISION, CURRENT_COMMIT).getCurrentRevision();
    assertThat(currentRevision.commit.parents.get(0).commit)
        .isEqualTo(results.get(targetBranch).getCommit().name());
    assertThat(currentRevision.conflicts).isNotNull();
    assertThat(currentRevision.conflicts.base).isEqualTo(baseCommit.name());
    assertThat(currentRevision.conflicts.ours)
        .isEqualTo(results.get(targetBranch).getCommit().name());
    assertThat(currentRevision.conflicts.theirs)
        .isEqualTo(results.get(sourceBranch).getCommit().name());
    assertThat(currentRevision.conflicts.mergeStrategy).isEqualTo(strategy);
    assertThat(currentRevision.conflicts.noBaseReason).isNull();
    assertThat(currentRevision.conflicts.containsConflicts).isTrue();

    // Verify that the file content in the created change is correct.
    // We expect that it has conflict markers to indicate the conflict.
    BinaryResult bin =
        gApi.changes().id(project.get(), change._number).current().file(fileName).content();
    ByteArrayOutputStream os = new ByteArrayOutputStream();
    bin.writeTo(os);
    String fileContent = new String(os.toByteArray(), UTF_8);
    assertThat(fileContent)
        .isEqualTo(
            String.format(
                """
                <<<<<<< TARGET BRANCH (%s %s)
                %s
                %s=======
                %s
                >>>>>>> SOURCE BRANCH (%s %s)
                """,
                projectOperations.project(project).getHead(targetBranch).getName(),
                targetSubject,
                targetContent,
                (useDiff3
                    ? String.format(
                        "||||||| BASE          (%s %s)\n",
                        baseCommit.getName(), baseCommit.getShortMessage())
                    : ""),
                sourceContent,
                projectOperations.project(project).getHead(sourceBranch).getName(),
                sourceSubject));

    // Verify the message that has been posted on the change.
    List<ChangeMessageInfo> messages = gApi.changes().id(change._number).messages();
    assertThat(messages).hasSize(1);
    assertThat(Iterables.getOnlyElement(messages).message)
        .isEqualTo(
            "Uploaded patch set 1.\n\n"
                + "The following files contain Git conflicts:\n"
                + "* "
                + fileName
                + "\n");
  }

  @Test
  public void createMergeChangeBetweenTwoInitialCommitsWithConflictsAllowedUsingRecursiveStrategy()
      throws Exception {
    testCreateMergeChangeBetweenTwoInitialCommitsConflictsAllowed(
        /* strategy= */ "recursive", /* useDiff3= */ false);
  }

  @Test
  @GerritConfig(name = "change.diff3ConflictView", value = "true")
  public void
      createMergeChangeBetweenTwoInitialCommitsWithConflictsAllowedUsingRecursiveStrategyAndDiff3()
          throws Exception {
    testCreateMergeChangeBetweenTwoInitialCommitsConflictsAllowed(
        /* strategy= */ "recursive", /* useDiff3= */ true);
  }

  @Test
  public void createMergeChangeBetweenTwoInitialCommitsWithConflictsAllowedUsingResolveStrategy()
      throws Exception {
    testCreateMergeChangeBetweenTwoInitialCommitsConflictsAllowed(
        /* strategy= */ "resolve", /* useDiff3= */ false);
  }

  @Test
  @GerritConfig(name = "change.diff3ConflictView", value = "true")
  public void
      createMergeChangeBetweenTwoInitialCommitsWithConflictsAllowedUsingResolveStrategyAndDiff3()
          throws Exception {
    testCreateMergeChangeBetweenTwoInitialCommitsConflictsAllowed(
        /* strategy= */ "resolve", /* useDiff3= */ true);
  }

  @Test
  public void
      createMergeChangeBetweenTwoInitialCommitsWithConflictsAllowedUsingUsingSimpleTwoWayInCoreStrategy()
          throws Exception {
    testCreateMergeChangeBetweenTwoInitialCommitsConflictsAllowed(
        /* strategy= */ "simple-two-way-in-core", /* useDiff3= */ false);
  }

  @Test
  @GerritConfig(name = "change.diff3ConflictView", value = "true")
  public void
      createMergeChangeBetweenTwoInitialCommitsWithConflictsAllowedUsingUsingSimpleTwoWayInCoreStrategyAndDiff3()
          throws Exception {
    testCreateMergeChangeBetweenTwoInitialCommitsConflictsAllowed(
        /* strategy= */ "simple-two-way-in-core", /* useDiff3= */ true);
  }

  @Test
  public void createMergeChangeBetweenTwoInitialCommitsWithConflictsAllowedUsingUsingOursStrategy()
      throws Exception {
    testCreateMergeChangeBetweenTwoInitialCommitsConflictsAllowed(
        /* strategy= */ "ours", /* useDiff3= */ false);
  }

  @Test
  @GerritConfig(name = "change.diff3ConflictView", value = "true")
  public void
      createMergeChangeBetweenTwoInitialCommitsWithConflictsAllowedUsingUsingOursStrategyAndDiff3()
          throws Exception {
    testCreateMergeChangeBetweenTwoInitialCommitsConflictsAllowed(
        /* strategy= */ "ours", /* useDiff3= */ true);
  }

  @Test
  public void
      createMergeChangeBetweenTwoInitialCommitsWithConflictsAllowedUsingUsingTheirsStrategy()
          throws Exception {
    testCreateMergeChangeBetweenTwoInitialCommitsConflictsAllowed(
        /* strategy= */ "theirs", /* useDiff3= */ false);
  }

  @Test
  @GerritConfig(name = "change.diff3ConflictView", value = "true")
  public void
      createMergeChangeBetweenTwoInitialCommitsWithConflictsAllowedUsingUsingTheirsStrategyAndDiff3()
          throws Exception {
    testCreateMergeChangeBetweenTwoInitialCommitsConflictsAllowed(
        /* strategy= */ "theirs", /* useDiff3= */ true);
  }

  private void testCreateMergeChangeBetweenTwoInitialCommitsConflictsAllowed(
      String strategy, boolean useDiff3) throws Exception {
    String fileName = "shared.txt";
    String sourceBranch = "sourceBranch";
    String sourceSubject = "source change";
    String sourceContent = "source content";
    String targetBranch = "targetBranch";
    String targetSubject = "target change";
    String targetContent = "target content";

    Project.NameKey projectWithoutInitialCommit =
        projectOperations.newProject().createEmptyCommit(false).create();

    // Create sourceBranch with an initial commit.
    TestRepository<InMemoryRepository> testRepo =
        cloneProject(projectWithoutInitialCommit, getCloneAsAccount(configRule.description()));
    RevCommit initialCommitSource =
        testRepo.parseBody(
            testRepo
                .commit()
                .message(sourceSubject)
                .insertChangeId()
                .add(fileName, sourceContent)
                .create());
    testRepo.reset(initialCommitSource);
    PushResult r = pushHead(testRepo, "refs/heads/" + sourceBranch);
    assertThat(r.getRemoteUpdate("refs/heads/" + sourceBranch).getStatus()).isEqualTo(Status.OK);

    // Create targetBranch with another initial commit.
    RevCommit initialCommitTarget =
        testRepo.parseBody(
            testRepo
                .commit()
                .message(targetSubject)
                .insertChangeId()
                .add(fileName, targetContent)
                .create());
    testRepo.reset(initialCommitTarget);
    r = pushHead(testRepo, "refs/heads/" + targetBranch);
    assertThat(r.getRemoteUpdate("refs/heads/" + targetBranch).getStatus()).isEqualTo(Status.OK);

    // Merge sourceBranch into targetBranch with conflicts allowed.
    ChangeInput in =
        newMergeChangeInput(
            projectWithoutInitialCommit,
            targetBranch,
            sourceBranch,
            /* strategy= */ strategy,
            /* allowConflicts= */ true);

    if ("ours".equals(strategy) || "theirs".equals(strategy)) {
      ChangeInfo change = assertCreateSucceeds(in);

      // Verify the conflicts information.
      RevisionInfo currentRevision =
          gApi.changes().id(change.id).get(CURRENT_REVISION, CURRENT_COMMIT).getCurrentRevision();
      assertThat(currentRevision.commit.parents.get(0).commit)
          .isEqualTo(initialCommitTarget.name());
      assertThat(currentRevision.conflicts).isNotNull();
      assertThat(currentRevision.conflicts.base).isNull();
      assertThat(currentRevision.conflicts.ours).isEqualTo(initialCommitTarget.name());
      assertThat(currentRevision.conflicts.theirs).isEqualTo(initialCommitSource.name());
      assertThat(currentRevision.conflicts.mergeStrategy).isEqualTo(strategy);
      assertThat(currentRevision.conflicts.noBaseReason)
          .isEqualTo(NoMergeBaseReason.ONE_SIDED_MERGE_STRATEGY);
      assertThat(currentRevision.conflicts.containsConflicts).isFalse();

      // Verify that the file content in the created change is correct.
      // We expect that it has conflict markers to indicate the conflict.
      BinaryResult bin =
          gApi.changes()
              .id(projectWithoutInitialCommit.get(), change._number)
              .current()
              .file(fileName)
              .content();
      ByteArrayOutputStream os = new ByteArrayOutputStream();
      bin.writeTo(os);
      String fileContent = new String(os.toByteArray(), UTF_8);
      assertThat(fileContent).isEqualTo("ours".equals(strategy) ? targetContent : sourceContent);

      return;
    }

    if ("simple-two-way-in-core".equals(strategy)) {
      assertCreateFails(
          in,
          BadRequestException.class,
          "merge with conflicts is not supported with merge strategy: simple-two-way-in-core");
      return;
    }

    ChangeInfo change = assertCreateSucceedsWithConflicts(in);

    // Verify the conflicts information.
    RevisionInfo currentRevision =
        gApi.changes().id(change.id).get(CURRENT_REVISION, CURRENT_COMMIT).getCurrentRevision();
    assertThat(currentRevision.commit.parents.get(0).commit).isEqualTo(initialCommitTarget.name());
    assertThat(currentRevision.conflicts).isNotNull();
    assertThat(currentRevision.conflicts.base).isNull();
    assertThat(currentRevision.conflicts.ours).isEqualTo(initialCommitTarget.name());
    assertThat(currentRevision.conflicts.theirs).isEqualTo(initialCommitSource.name());
    assertThat(currentRevision.conflicts.mergeStrategy).isEqualTo(strategy);
    assertThat(currentRevision.conflicts.noBaseReason)
        .isEqualTo(NoMergeBaseReason.NO_COMMON_ANCESTOR);
    assertThat(currentRevision.conflicts.containsConflicts).isTrue();

    // Verify that the file content in the created change is correct.
    // We expect that it has conflict markers to indicate the conflict.
    BinaryResult bin =
        gApi.changes()
            .id(projectWithoutInitialCommit.get(), change._number)
            .current()
            .file(fileName)
            .content();
    ByteArrayOutputStream os = new ByteArrayOutputStream();
    bin.writeTo(os);
    String fileContent = new String(os.toByteArray(), UTF_8);
    assertThat(fileContent)
        .isEqualTo(
            String.format(
                """
                <<<<<<< TARGET BRANCH (%s %s)
                %s
                %s=======
                %s
                >>>>>>> SOURCE BRANCH (%s %s)
                """,
                projectOperations
                    .project(projectWithoutInitialCommit)
                    .getHead(targetBranch)
                    .getName(),
                targetSubject,
                targetContent,
                (useDiff3 ? "||||||| BASE          (no common ancestor)\n" : ""),
                sourceContent,
                projectOperations
                    .project(projectWithoutInitialCommit)
                    .getHead(sourceBranch)
                    .getName(),
                sourceSubject));

    // Verify the message that has been posted on the change.
    List<ChangeMessageInfo> messages =
        gApi.changes().id(projectWithoutInitialCommit.get(), change._number).messages();
    assertThat(messages).hasSize(1);
    assertThat(Iterables.getOnlyElement(messages).message)
        .isEqualTo(
            String.format(
                """
                Uploaded patch set 1.

                The following files contain Git conflicts:
                * %s
                """,
                fileName));
  }

  @Test
  public void
      createMergeChangeBetweenTwoCommitsThatHaveMultipleMergeBasesWithConflictsAllowedUsingRecursiveStrategy()
          throws Exception {
    testCreateMergeChangeBetweenTwoCommitsThatHaveMultipleMergeBasesConflictsAllowed(
        /* strategy= */ "recursive", /* useDiff3= */ false);
  }

  @Test
  @GerritConfig(name = "change.diff3ConflictView", value = "true")
  public void
      createMergeChangeBetweenTwoCommitsThatHaveMultipleMergeBasesWithConflictsAllowedUsingRecursiveStrategyAndDiff3()
          throws Exception {
    testCreateMergeChangeBetweenTwoCommitsThatHaveMultipleMergeBasesConflictsAllowed(
        /* strategy= */ "recursive", /* useDiff3= */ true);
  }

  @Test
  public void
      createMergeChangeBetweenTwoCommitsThatHaveMultipleMergeBasesWithConflictsAllowedUsingResolveStrategy()
          throws Exception {
    testCreateMergeChangeBetweenTwoCommitsThatHaveMultipleMergeBasesConflictsAllowed(
        /* strategy= */ "resolve", /* useDiff3= */ false);
  }

  @Test
  @GerritConfig(name = "change.diff3ConflictView", value = "true")
  public void
      createMergeChangeBetweenTwoCommitsThatHaveMultipleMergeBasesWithConflictsAllowedUsingResolveStrategyAndDiff3()
          throws Exception {
    testCreateMergeChangeBetweenTwoCommitsThatHaveMultipleMergeBasesConflictsAllowed(
        /* strategy= */ "resolve", /* useDiff3= */ true);
  }

  @Test
  public void
      createMergeChangeBetweenTwoCommitsThatHaveMultipleMergeBasesWithConflictsAllowedUsingOursStrategy()
          throws Exception {
    testCreateMergeChangeBetweenTwoCommitsThatHaveMultipleMergeBasesConflictsAllowed(
        /* strategy= */ "ours", /* useDiff3= */ false);
  }

  @Test
  public void
      createMergeChangeBetweenTwoCommitsThatHaveMultipleMergeBasesWithConflictsAllowedUsingTheirsStrategy()
          throws Exception {
    testCreateMergeChangeBetweenTwoCommitsThatHaveMultipleMergeBasesConflictsAllowed(
        /* strategy= */ "theirs", /* useDiff3= */ false);
  }

  @Test
  public void
      createMergeChangeBetweenTwoCommitsThatHaveMultipleMergeBasesWithConflictsAllowedUsingSimpleTwoWayInCoreStrategy()
          throws Exception {
    testCreateMergeChangeBetweenTwoCommitsThatHaveMultipleMergeBasesConflictsAllowed(
        /* strategy= */ "simple-two-way-in-core", /* useDiff3= */ false);
  }

  private void testCreateMergeChangeBetweenTwoCommitsThatHaveMultipleMergeBasesConflictsAllowed(
      String strategy, boolean useDiff3) throws Exception {
    String sourceBranch = "sourceBranch";
    String targetBranch = "targetBranch";

    // Create source and target branch with non-conflicting commits.
    // Later these commits will become the base commits for the criss-cross-merge.
    ImmutableMap<String, Result> results =
        changeInTwoBranches(
            sourceBranch,
            "base 1",
            "a.txt",
            "a content",
            targetBranch,
            "base 2",
            "b.txt",
            "b content");
    RevCommit baseCommitInSource = results.get(sourceBranch).getCommit();
    RevCommit baseCommitInTarget = results.get(targetBranch).getCommit();

    // Create merge commits in both branches (1. merge the target branch into the source branch, 2.
    // merge the source branch into the target branch).
    PushOneCommit mergeCommitInSource = pushFactory.create(user.newIdent(), testRepo);
    mergeCommitInSource.setParents(ImmutableList.of(baseCommitInSource, baseCommitInTarget));
    mergeCommitInSource.to("refs/heads/" + sourceBranch).assertOkStatus();
    PushOneCommit mergeCommitInTarget = pushFactory.create(user.newIdent(), testRepo);
    mergeCommitInTarget.setParents(ImmutableList.of(baseCommitInTarget, baseCommitInSource));
    mergeCommitInTarget.to("refs/heads/" + targetBranch).assertOkStatus();

    // Create conflicting commits in both branches.
    String fileName = "shared.txt";
    String sourceSubject = "source change";
    String sourceContent = "source content";
    String targetSubject = "target change";
    String targetContent = "target content";
    PushOneCommit pushConflictingCommitInSource =
        pushFactory.create(user.newIdent(), testRepo, sourceSubject, fileName, sourceContent);
    pushConflictingCommitInSource.setParent(
        projectOperations.project(project).getHead(sourceBranch));
    PushOneCommit.Result pushConflictingCommitInSourceResult =
        pushConflictingCommitInSource.to("refs/heads/" + sourceBranch);
    pushConflictingCommitInSourceResult.assertOkStatus();
    PushOneCommit pushConflictingCommitInTarget =
        pushFactory.create(user.newIdent(), testRepo, targetSubject, fileName, targetContent);
    pushConflictingCommitInTarget.setParent(
        projectOperations.project(project).getHead(targetBranch));
    PushOneCommit.Result pushConflictingCommitInTargetResult =
        pushConflictingCommitInTarget.to("refs/heads/" + targetBranch);
    pushConflictingCommitInTargetResult.assertOkStatus();

    // Merge the source branch into the target with conflicts allowed. This is a criss-cross-merge:
    //
    //                   (criss-cross-merge)
    //                     /             \
    // (conflictingCommitInSource) (conflictingCommitInTarget)
    //                    |               |
    //       (mergeCommitInSource) (mergeCommitInTarget)
    //                    |       X       |
    //       (baseCommitInSource)  (baseCommitInTarget)
    //                     \           /
    //                    (initialCommit)
    ChangeInput mergeInput =
        newMergeChangeInput(targetBranch, sourceBranch, strategy, /* allowConflicts= */ true);

    // The resolve/simple-two-way-in-core strategy doesn't support criss-cross-merges.
    if ("resolve".equals(strategy) || "simple-two-way-in-core".equals(strategy)) {
      assertCreateFails(
          mergeInput,
          ResourceConflictException.class,
          "Cannot create merge commit: No merge base could be determined."
              + " Reason=MULTIPLE_MERGE_BASES_NOT_SUPPORTED.");
      return;
    }

    // The ours/theirs strategy never results in conflicts.
    if ("ours".equals(strategy) || "theirs".equals(strategy)) {
      ChangeInfo change = assertCreateSucceeds(mergeInput);

      // Verify the conflicts information
      RevisionInfo currentRevision =
          gApi.changes().id(change.id).get(CURRENT_REVISION, CURRENT_COMMIT).getCurrentRevision();
      assertThat(currentRevision.commit.parents.get(0).commit)
          .isEqualTo(pushConflictingCommitInTargetResult.getCommit().name());
      assertThat(currentRevision.commit.parents.get(1).commit)
          .isEqualTo(pushConflictingCommitInSourceResult.getCommit().name());
      assertThat(currentRevision.conflicts).isNotNull();
      assertThat(currentRevision.conflicts.base).isNull();
      assertThat(currentRevision.conflicts.ours)
          .isEqualTo(pushConflictingCommitInTargetResult.getCommit().name());
      assertThat(currentRevision.conflicts.theirs)
          .isEqualTo(pushConflictingCommitInSourceResult.getCommit().name());
      assertThat(currentRevision.conflicts.mergeStrategy).isEqualTo(strategy);
      assertThat(currentRevision.conflicts.noBaseReason)
          .isEqualTo(NoMergeBaseReason.ONE_SIDED_MERGE_STRATEGY);
      assertThat(currentRevision.conflicts.containsConflicts).isFalse();

      // Verify that the file content in the created change is correct.
      // We expect that it doesn't have conflict markers and the content from "ours" version was
      // used.
      BinaryResult bin =
          gApi.changes().id(project.get(), change._number).current().file(fileName).content();
      ByteArrayOutputStream os = new ByteArrayOutputStream();
      bin.writeTo(os);
      String fileContent = new String(os.toByteArray(), UTF_8);
      assertThat(fileContent).isEqualTo("ours".equals(strategy) ? targetContent : sourceContent);

      return;
    }

    assume().that(strategy).isEqualTo("recursive");
    ChangeInfo change = assertCreateSucceedsWithConflicts(mergeInput);

    // Verify the conflicts information
    RevisionInfo currentRevision =
        gApi.changes().id(change.id).get(CURRENT_REVISION, CURRENT_COMMIT).getCurrentRevision();
    assertThat(currentRevision.commit.parents.get(0).commit)
        .isEqualTo(pushConflictingCommitInTargetResult.getCommit().name());
    assertThat(currentRevision.commit.parents.get(1).commit)
        .isEqualTo(pushConflictingCommitInSourceResult.getCommit().name());
    assertThat(currentRevision.conflicts).isNotNull();
    assertThat(currentRevision.conflicts.base).isNull();
    assertThat(currentRevision.conflicts.ours)
        .isEqualTo(pushConflictingCommitInTargetResult.getCommit().name());
    assertThat(currentRevision.conflicts.theirs)
        .isEqualTo(pushConflictingCommitInSourceResult.getCommit().name());
    assertThat(currentRevision.conflicts.mergeStrategy).isEqualTo(strategy);
    assertThat(currentRevision.conflicts.noBaseReason).isEqualTo(NoMergeBaseReason.COMPUTED_BASE);
    assertThat(currentRevision.conflicts.containsConflicts).isTrue();

    // Verify that the file content in the created change is correct.
    // We expect that it has conflict markers to indicate the conflict.
    BinaryResult bin =
        gApi.changes().id(project.get(), change._number).current().file(fileName).content();
    ByteArrayOutputStream os = new ByteArrayOutputStream();
    bin.writeTo(os);
    String fileContent = new String(os.toByteArray(), UTF_8);
    assertThat(fileContent)
        .isEqualTo(
            String.format(
                """
                <<<<<<< TARGET BRANCH (%s %s)
                %s
                %s=======
                %s
                >>>>>>> SOURCE BRANCH (%s %s)
                """,
                pushConflictingCommitInTargetResult.getCommit().name(),
                targetSubject,
                targetContent,
                (useDiff3 ? "||||||| BASE          (computed base)\n" : ""),
                sourceContent,
                pushConflictingCommitInSourceResult.getCommit().name(),
                sourceSubject));

    // Verify the message that has been posted on the change.
    List<ChangeMessageInfo> messages = gApi.changes().id(project.get(), change._number).messages();
    assertThat(messages).hasSize(1);
    assertThat(Iterables.getOnlyElement(messages).message)
        .isEqualTo(
            String.format(
                """
                Uploaded patch set 1.

                The following files contain Git conflicts:
                * %s
                """,
                fileName));
  }

  @Test
  public void
      createMergeChangeBetweenTwoCommitsThatHaveMultipleConflictingMergeBasesWithConflictsAllowed()
          throws Exception {
    testCreateMergeChangeBetweenTwoCommitsThatHaveMultipleConflictingMergeBasesConflictsAllowed(
        /* useDiff3= */ false);
  }

  @Test
  @GerritConfig(name = "change.diff3ConflictView", value = "true")
  public void
      createMergeChangeBetweenTwoCommitsThatHaveMultipleConflictingMergeBasesWithConflictsAllowedUsingDiff3()
          throws Exception {
    testCreateMergeChangeBetweenTwoCommitsThatHaveMultipleConflictingMergeBasesConflictsAllowed(
        /* useDiff3= */ true);
  }

  private void
      testCreateMergeChangeBetweenTwoCommitsThatHaveMultipleConflictingMergeBasesConflictsAllowed(
          boolean useDiff3) throws Exception {
    String sourceBranch = "sourceBranch";
    String targetBranch = "targetBranch";

    // Create source and target branch with conflicting commits.
    // Later these commits will become the base commits for the criss-cross-merge.
    String baseFile = "base.txt";
    String baseContentSource = "base source";
    String baseContentTarget = "base target";
    ImmutableMap<String, Result> results =
        changeInTwoBranches(
            sourceBranch,
            "base 1",
            baseFile,
            baseContentSource,
            targetBranch,
            "base 2",
            baseFile,
            baseContentTarget);
    RevCommit baseCommitInSource = results.get(sourceBranch).getCommit();
    RevCommit baseCommitInTarget = results.get(targetBranch).getCommit();

    // Create merge commits in both branches (1. merge the target branch into the source branch, 2.
    // merge the source branch into the target branch).
    PushOneCommit mergeCommitInSource =
        pushFactory.create(
            user.newIdent(), testRepo, "Merge in Source", baseFile, baseContentSource);
    mergeCommitInSource.setParents(ImmutableList.of(baseCommitInSource, baseCommitInTarget));
    mergeCommitInSource.to("refs/heads/" + sourceBranch).assertOkStatus();
    PushOneCommit mergeCommitInTarget =
        pushFactory.create(
            user.newIdent(), testRepo, "Merge in Target", baseFile, baseContentTarget);
    mergeCommitInTarget.setParents(ImmutableList.of(baseCommitInTarget, baseCommitInSource));
    mergeCommitInTarget.to("refs/heads/" + targetBranch).assertOkStatus();

    // Create conflicting commits in both branches.
    String fileName = "shared.txt";
    String sourceSubject = "source change";
    String sourceContent = "source content";
    String targetSubject = "target change";
    String targetContent = "target content";
    PushOneCommit pushConflictingCommitInSource =
        pushFactory.create(user.newIdent(), testRepo, sourceSubject, fileName, sourceContent);
    pushConflictingCommitInSource.setParent(
        projectOperations.project(project).getHead(sourceBranch));
    PushOneCommit.Result pushConflictingCommitInSourceResult =
        pushConflictingCommitInSource.to("refs/heads/" + sourceBranch);
    pushConflictingCommitInSourceResult.assertOkStatus();
    PushOneCommit pushConflictingCommitInTarget =
        pushFactory.create(user.newIdent(), testRepo, targetSubject, fileName, targetContent);
    pushConflictingCommitInTarget.setParent(
        projectOperations.project(project).getHead(targetBranch));
    PushOneCommit.Result pushConflictingCommitInTargetResult =
        pushConflictingCommitInTarget.to("refs/heads/" + targetBranch);
    pushConflictingCommitInTargetResult.assertOkStatus();

    // Merge the source branch into the target with conflicts allowed. This is a criss-cross-merge:
    //
    //                   (criss-cross-merge)
    //                     /             \
    // (conflictingCommitInSource) (conflictingCommitInTarget)
    //                    |               |
    //       (mergeCommitInSource) (mergeCommitInTarget)
    //                    |       X       |
    //       (baseCommitInSource)  (baseCommitInTarget)
    //                     \           /
    //                    (initialCommit)
    ChangeInput mergeInput =
        newMergeChangeInput(targetBranch, sourceBranch, "recursive", /* allowConflicts= */ true);

    ChangeInfo change = assertCreateSucceedsWithConflicts(mergeInput);

    // Verify the conflicts information
    RevisionInfo currentRevision =
        gApi.changes().id(change.id).get(CURRENT_REVISION, CURRENT_COMMIT).getCurrentRevision();
    assertThat(currentRevision.commit.parents.get(0).commit)
        .isEqualTo(pushConflictingCommitInTargetResult.getCommit().name());
    assertThat(currentRevision.commit.parents.get(1).commit)
        .isEqualTo(pushConflictingCommitInSourceResult.getCommit().name());
    assertThat(currentRevision.conflicts).isNotNull();
    assertThat(currentRevision.conflicts.base).isNull();
    assertThat(currentRevision.conflicts.ours)
        .isEqualTo(pushConflictingCommitInTargetResult.getCommit().name());
    assertThat(currentRevision.conflicts.theirs)
        .isEqualTo(pushConflictingCommitInSourceResult.getCommit().name());
    assertThat(currentRevision.conflicts.mergeStrategy).isEqualTo("recursive");
    assertThat(currentRevision.conflicts.noBaseReason).isEqualTo(NoMergeBaseReason.COMPUTED_BASE);
    assertThat(currentRevision.conflicts.containsConflicts).isTrue();

    // Verify that the content of the file that was conflicting in the bases is correct.
    // We expect that it has conflict markers to indicate the conflict and that the base version
    // that is computed by the recursive merge is a auto merge of the two bases, containing conflict
    // markers as well.
    String computedBaseContent =
        String.format(
            """
            <<<<<<< OURS
            %s
            =======
            %s
            >>>>>>> THEIRS
            """,
            baseContentTarget, baseContentSource);
    BinaryResult bin =
        gApi.changes().id(project.get(), change._number).current().file(baseFile).content();
    ByteArrayOutputStream os = new ByteArrayOutputStream();
    bin.writeTo(os);
    String fileContent = new String(os.toByteArray(), UTF_8);
    assertThat(fileContent)
        .isEqualTo(
            String.format(
                """
                <<<<<<< TARGET BRANCH (%s %s)
                %s
                %s=======
                %s
                >>>>>>> SOURCE BRANCH (%s %s)
                """,
                pushConflictingCommitInTargetResult.getCommit().name(),
                targetSubject,
                baseContentTarget,
                (useDiff3 ? "||||||| BASE          (computed base)\n" + computedBaseContent : ""),
                baseContentSource,
                pushConflictingCommitInSourceResult.getCommit().name(),
                sourceSubject));

    // Verify that the file content in the created change is correct.
    // We expect that it has conflict markers to indicate the conflict.
    bin = gApi.changes().id(project.get(), change._number).current().file(fileName).content();
    os = new ByteArrayOutputStream();
    bin.writeTo(os);
    fileContent = new String(os.toByteArray(), UTF_8);
    assertThat(fileContent)
        .isEqualTo(
            String.format(
                """
                <<<<<<< TARGET BRANCH (%s %s)
                %s
                %s=======
                %s
                >>>>>>> SOURCE BRANCH (%s %s)
                """,
                pushConflictingCommitInTargetResult.getCommit().name(),
                targetSubject,
                targetContent,
                (useDiff3 ? "||||||| BASE          (computed base)\n" : ""),
                sourceContent,
                pushConflictingCommitInSourceResult.getCommit().name(),
                sourceSubject));

    // Verify the message that has been posted on the change.
    List<ChangeMessageInfo> messages = gApi.changes().id(project.get(), change._number).messages();
    assertThat(messages).hasSize(1);
    assertThat(Iterables.getOnlyElement(messages).message)
        .isEqualTo(
            String.format(
                """
                Uploaded patch set 1.

                The following files contain Git conflicts:
                * %s
                * %s
                """,
                baseFile, fileName));
  }

  @Test
  public void createMergeChangeFailsWithConflictIfThereAreTooManyCommonPredecessors()
      throws Exception {
    // Create an initial commit in master.
    Result initialCommit =
        pushFactory
            .create(user.newIdent(), testRepo, "initial commit", "readme.txt", "initial commit")
            .to("refs/heads/master");
    initialCommit.assertOkStatus();

    String file = "shared.txt";
    List<RevCommit> parents = new ArrayList<>();
    // RecursiveMerger#MAX_BASES = 200, cannot use RecursiveMerger#MAX_BASES as it is not static.
    int maxBases = 200;

    // Create more than RecursiveMerger#MAX_BASES base commits.
    for (int i = 1; i <= maxBases + 1; i++) {
      parents.add(
          testRepo
              .commit()
              .message("Base " + i)
              .add(file, "content " + i)
              .parent(initialCommit.getCommit())
              .create());
    }

    // Create 2 branches.
    String branchA = "branchA";
    String branchB = "branchB";
    createBranch(BranchNameKey.create(project, branchA));
    createBranch(BranchNameKey.create(project, branchB));

    // Push an octopus merge to both of the branches.
    Result octopusA =
        pushFactory
            .create(user.newIdent(), testRepo)
            .setParents(parents)
            .to("refs/heads/" + branchA);
    octopusA.assertOkStatus();

    Result octopusB =
        pushFactory
            .create(user.newIdent(), testRepo)
            .setParents(parents)
            .to("refs/heads/" + branchB);
    octopusB.assertOkStatus();

    // Creating a merge commit for the 2 octopus commits fails, because they have more than
    // RecursiveMerger#MAX_BASES common predecessors.
    assertCreateFails(
        newMergeChangeInput("branchA", "branchB", ""),
        ResourceConflictException.class,
        "Cannot create merge commit: No merge base could be determined."
            + " Reason=TOO_MANY_MERGE_BASES.");
  }

  @Test
  public void updateCommitMessageOfMergeChangeWithConflicts() throws Exception {
    // Create a change with a merge commit that contains conflicts
    String sourceBranch = "source";
    String targetBranch = "target";
    ImmutableMap<String, Result> results =
        changeInTwoBranches(
            sourceBranch,
            "source change",
            "shared.txt",
            "source content",
            targetBranch,
            "target change",
            "shared.txt",
            "target content");
    RevCommit baseCommit = results.get("master").getCommit();
    ChangeInput in =
        newMergeChangeInput(targetBranch, sourceBranch, "", /* allowConflicts= */ true);
    in.subject = "Merge " + sourceBranch + " to " + targetBranch;
    ChangeInfo change = assertCreateSucceedsWithConflicts(in);

    // Verify the conflicts information
    RevisionInfo currentRevision =
        gApi.changes().id(change.id).get(CURRENT_REVISION, CURRENT_COMMIT).getCurrentRevision();
    assertThat(currentRevision._number).isEqualTo(1);
    assertThat(currentRevision.commit.parents.get(0).commit)
        .isEqualTo(results.get(targetBranch).getCommit().name());
    assertThat(currentRevision.conflicts).isNotNull();
    assertThat(currentRevision.conflicts.base).isEqualTo(baseCommit.name());
    assertThat(currentRevision.conflicts.ours)
        .isEqualTo(results.get(targetBranch).getCommit().name());
    assertThat(currentRevision.conflicts.theirs)
        .isEqualTo(results.get(sourceBranch).getCommit().name());
    assertThat(currentRevision.conflicts.mergeStrategy).isEqualTo("recursive");
    assertThat(currentRevision.conflicts.noBaseReason).isNull();
    assertThat(currentRevision.conflicts.containsConflicts).isTrue();

    // Update the commit message
    gApi.changes()
        .id(change.id)
        .setMessage(
            "Merge "
                + sourceBranch
                + " branch into "
                + targetBranch
                + " branch\n\nChange-Id: "
                + change.changeId);

    // Verify that the conflicts information has been copied
    currentRevision =
        gApi.changes().id(change.id).get(CURRENT_REVISION, CURRENT_COMMIT).getCurrentRevision();
    assertThat(currentRevision._number).isEqualTo(2);
    assertThat(currentRevision.commit.parents.get(0).commit)
        .isEqualTo(results.get(targetBranch).getCommit().name());
    assertThat(currentRevision.conflicts).isNotNull();
    assertThat(currentRevision.conflicts.base).isEqualTo(baseCommit.name());
    assertThat(currentRevision.conflicts.ours)
        .isEqualTo(results.get(targetBranch).getCommit().name());
    assertThat(currentRevision.conflicts.theirs)
        .isEqualTo(results.get(sourceBranch).getCommit().name());
    assertThat(currentRevision.conflicts.mergeStrategy).isEqualTo("recursive");
    assertThat(currentRevision.conflicts.noBaseReason).isNull();
    assertThat(currentRevision.conflicts.containsConflicts).isTrue();
  }

  @Test
  public void invalidSource() throws Exception {
    changeInTwoBranches("branchA", "a.txt", "branchB", "b.txt");
    ChangeInput in = newMergeChangeInput("branchA", "invalid", "");
    assertCreateFails(in, BadRequestException.class, "Cannot resolve 'invalid' to a commit");
  }

  @Test
  public void invalidStrategy() throws Exception {
    changeInTwoBranches("branchA", "a.txt", "branchB", "b.txt");
    ChangeInput in = newMergeChangeInput("branchA", "branchB", "octopus");
    assertCreateFails(in, BadRequestException.class, "invalid merge strategy: octopus");
  }

  @Test
  public void alreadyMerged() throws Exception {
    ObjectId c0 =
        testRepo
            .branch("HEAD")
            .commit()
            .insertChangeId()
            .message("first commit")
            .add("a.txt", "a contents ")
            .create();
    testRepo
        .git()
        .push()
        .setRemote("origin")
        .setRefSpecs(new RefSpec("HEAD:refs/heads/master"))
        .call();

    testRepo
        .branch("HEAD")
        .commit()
        .insertChangeId()
        .message("second commit")
        .add("b.txt", "b contents ")
        .create();
    testRepo
        .git()
        .push()
        .setRemote("origin")
        .setRefSpecs(new RefSpec("HEAD:refs/heads/master"))
        .call();

    ChangeInput in = newMergeChangeInput("master", c0.getName(), "");
    assertCreateFails(
        in, ChangeAlreadyMergedException.class, "'" + c0.getName() + "' has already been merged");
  }

  @Test
  public void onlyContentMerged() throws Exception {
    testRepo
        .branch("HEAD")
        .commit()
        .insertChangeId()
        .message("first commit")
        .add("a.txt", "a contents ")
        .create();
    testRepo
        .git()
        .push()
        .setRemote("origin")
        .setRefSpecs(new RefSpec("HEAD:refs/heads/master"))
        .call();

    // create a change, and cherrypick into master
    PushOneCommit.Result cId = createChange();
    RevCommit commitId = cId.getCommit();
    CherryPickInput cpi = new CherryPickInput();
    cpi.destination = "master";
    cpi.message = "cherry pick the commit";
    ChangeApi orig = gApi.changes().id(cId.getChangeId());
    ChangeApi cherry = orig.current().cherryPick(cpi);
    cherry.current().review(ReviewInput.approve());
    cherry.current().submit();

    ObjectId remoteId = projectOperations.project(project).getHead("master");
    assertThat(remoteId).isNotEqualTo(commitId);

    ChangeInput in = newMergeChangeInput("master", commitId.getName(), "");
    assertCreateSucceeds(in);
  }

  @Test
  public void createChangeOnExistingBranchNotPermitted() throws Exception {
    createBranch(BranchNameKey.create(project, "foo"));
    projectOperations
        .project(project)
        .forUpdate()
        // Allow reading for refs/meta/config so that the project is visible to the user. Otherwise
        // the request will fail with an UnprocessableEntityException "Project not found:".
        .add(allow(READ).ref("refs/meta/config").group(REGISTERED_USERS))
        .add(block(READ).ref("refs/heads/*").group(REGISTERED_USERS))
        .update();
    requestScopeOperations.setApiUser(user.id());
    ChangeInput input = newChangeInput(ChangeStatus.NEW);
    input.branch = "foo";

    assertCreateFails(input, ResourceNotFoundException.class, "ref refs/heads/foo not found");
  }

  @Test
  public void createChangeOnNonExistingBranch() throws Exception {
    requestScopeOperations.setApiUser(user.id());
    ChangeInput input = newChangeInput(ChangeStatus.NEW);
    input.branch = "foo";
    input.newBranch = true;
    assertCreateSucceeds(input);
  }

  @Test
  public void createChangeOnNonExistingBranchNotPermitted() throws Exception {
    projectOperations
        .project(project)
        .forUpdate()
        // Allow reading for refs/meta/config so that the project is visible to the user. Otherwise
        // the request will fail with an UnprocessableEntityException "Project not found:".
        .add(allow(READ).ref("refs/meta/config").group(REGISTERED_USERS))
        .add(block(READ).ref("refs/heads/*").group(REGISTERED_USERS))
        .update();
    requestScopeOperations.setApiUser(user.id());
    ChangeInput input = newChangeInput(ChangeStatus.NEW);
    input.branch = "foo";
    // sets this option to be true to make sure permission check happened before this option could
    // be considered.
    input.newBranch = true;

    assertCreateFails(input, ResourceNotFoundException.class, "ref refs/heads/foo not found");
  }

  @Test
  public void createMergeChangeOnNonExistingBranchNotPossible() throws Exception {
    requestScopeOperations.setApiUser(user.id());
    ChangeInput input = newMergeChangeInput("foo", "master", "");
    input.newBranch = true;
    assertCreateFails(
        input, BadRequestException.class, "Cannot create merge: destination branch does not exist");
  }

  @Test
  public void createChangeWithBothMergeAndPatch_fails() throws Exception {
    ChangeInput input = newMergeChangeInput("foo", "master", "");
    input.patch = new ApplyPatchInput();
    assertCreateFails(
        input, BadRequestException.class, "Only one of `merge` and `patch` arguments can be set");
  }

  private static final String PATCH_FILE_NAME = "a_file.txt";
  private static final String PATCH_NEW_FILE_CONTENT = "First added line\nSecond added line\n";
  private static final String PATCH_INPUT =
      "diff --git a/a_file.txt b/a_file.txt\n"
          + "new file mode 100644\n"
          + "index 0000000..f0eec86\n"
          + "--- /dev/null\n"
          + "+++ b/a_file.txt\n"
          + "@@ -0,0 +1,2 @@\n"
          + "+First added line\n"
          + "+Second added line\n";
  private static final String MODIFICATION_PATCH_INPUT =
      "diff --git a/a_file.txt b/a_file.txt\n"
          + "new file mode 100644\n"
          + "--- a/a_file.txt\n"
          + "+++ b/a_file.txt.txt\n"
          + "@@ -1,2 +1 @@\n"
          + "-First original line\n"
          + "-Second original line\n"
          + "+Modified line\n";

  @Test
  public void createPatchApplyingChange_success() throws Exception {
    createBranch(BranchNameKey.create(project, "other"));
    ChangeInput input = newPatchApplyingChangeInput("other", PATCH_INPUT);

    ChangeInfo info = assertCreateSucceeds(input);

    DiffInfo diff = gApi.changes().id(info.id).current().file(PATCH_FILE_NAME).diff();
    assertDiffForNewFile(diff, info.currentRevision, PATCH_FILE_NAME, PATCH_NEW_FILE_CONTENT);
    assertThat(info.revisions.get(info.currentRevision).commit.message)
        .isEqualTo("apply patch to other\n\nChange-Id: " + info.changeId + "\n");
  }

  @Test
  public void createPatchApplyingChange_fromGerritPatch_success() throws Exception {
    String head = getHead(repo(), HEAD).name();
    createBranchWithRevision(BranchNameKey.create(project, "branch"), head);
    PushOneCommit.Result baseCommit =
        createChange("Add file", PATCH_FILE_NAME, PATCH_NEW_FILE_CONTENT);
    baseCommit.assertOkStatus();
    BinaryResult originalPatch = gApi.changes().id(baseCommit.getChangeId()).current().patch();
    createBranchWithRevision(BranchNameKey.create(project, "other"), head);
    ChangeInput input = newPatchApplyingChangeInput("other", originalPatch.asString());

    ChangeInfo info = assertCreateSucceeds(input);

    DiffInfo diff = gApi.changes().id(info.id).current().file(PATCH_FILE_NAME).diff();
    assertDiffForNewFile(diff, info.currentRevision, PATCH_FILE_NAME, PATCH_NEW_FILE_CONTENT);
  }

  @Test
  public void createPatchApplyingChange_fromGerritPatchUsingRest_success() throws Exception {
    String head = getHead(repo(), HEAD).name();
    createBranchWithRevision(BranchNameKey.create(project, "branch"), head);
    PushOneCommit.Result baseCommit =
        createChange("Add file", PATCH_FILE_NAME, PATCH_NEW_FILE_CONTENT);
    baseCommit.assertOkStatus();
    createBranchWithRevision(BranchNameKey.create(project, "other"), head);
    RestResponse patchResp =
        userRestSession.get("/changes/" + baseCommit.getChangeId() + "/revisions/current/patch");
    patchResp.assertOK();
    String originalPatch = new String(Base64.decode(patchResp.getEntityContent()), UTF_8);
    ChangeInput input = newPatchApplyingChangeInput("other", originalPatch);

    ChangeInfo info = assertCreateSucceedsUsingRest(input);

    DiffInfo diff = gApi.changes().id(info.id).current().file(PATCH_FILE_NAME).diff();
    assertDiffForNewFile(diff, info.currentRevision, PATCH_FILE_NAME, PATCH_NEW_FILE_CONTENT);
  }

  @Test
  public void createPatchApplyingChange_withParentChange_success() throws Exception {
    Result change = createChange();
    ChangeInput input = newPatchApplyingChangeInput("other", PATCH_INPUT);
    input.baseChange = change.getChangeId();

    ChangeInfo info = assertCreateSucceeds(input);

    assertThat(gApi.changes().id(info.id).current().commit(false).parents.get(0).commit)
        .isEqualTo(change.getCommit().getId().name());
    DiffInfo diff = gApi.changes().id(info.id).current().file(PATCH_FILE_NAME).diff();
    assertDiffForNewFile(diff, info.currentRevision, PATCH_FILE_NAME, PATCH_NEW_FILE_CONTENT);
  }

  @Test
  public void createPatchApplyingChange_withParentCommit_success() throws Exception {
    Ref previousHead = testRepo.getRepository().exactRef("HEAD");
    createBranch(BranchNameKey.create(project, "other"));
    Result baseChange = createChange("refs/heads/other");
    /* Reset HEAD before creating change against master as to not create an implicit merge. */
    testRepo.reset(previousHead.getObjectId());
    PushOneCommit.Result ignoredCommit = createChange();
    ignoredCommit.assertOkStatus();
    ChangeInput input = newPatchApplyingChangeInput("other", PATCH_INPUT);
    input.baseCommit = baseChange.getCommit().getId().name();

    ChangeInfo info = assertCreateSucceeds(input);

    assertThat(gApi.changes().id(info.id).current().commit(false).parents.get(0).commit)
        .isEqualTo(input.baseCommit);
    DiffInfo diff = gApi.changes().id(info.id).current().file(PATCH_FILE_NAME).diff();
    assertDiffForNewFile(diff, info.currentRevision, PATCH_FILE_NAME, PATCH_NEW_FILE_CONTENT);
  }

  @Test
  public void createPatchApplyingChange_withEmptyTip_fails() throws Exception {
    ChangeInput input = newPatchApplyingChangeInput("foo", "patch");
    input.newBranch = true;
    assertCreateFails(
        input, BadRequestException.class, "Cannot apply patch on top of an empty tree");
  }

  @Test
  public void createPatchApplyingChange_fromBadPatch_fails() throws Exception {
    final String invalidPatch = "@@ -2,2 +2,3 @@ a\n" + " b\n" + "+c\n" + " d";
    createBranch(BranchNameKey.create(project, "other"));
    ChangeInput input = newPatchApplyingChangeInput("other", invalidPatch);
    assertCreateFails(input, BadRequestException.class, "Invalid patch format");
  }

  @Test
  public void createPatchApplyingChange_withAuthorOverride_success() throws Exception {
    createBranch(BranchNameKey.create(project, "other"));
    ChangeInput input = newPatchApplyingChangeInput("other", PATCH_INPUT);
    input.author = new AccountInput();
    input.author.email = "gerritlessjane@invalid";
    // This is an email address that doesn't exist as account on the Gerrit server.
    input.author.name = "Gerritless Jane";
    ChangeInfo info = assertCreateSucceeds(input);

    RevisionApi rApi = gApi.changes().id(info.id).current();
    GitPerson author = rApi.commit(false).author;
    assertThat(author).email().isEqualTo(input.author.email);
    assertThat(author).name().isEqualTo(input.author.name);
    GitPerson committer = rApi.commit(false).committer;
    assertThat(committer).email().isEqualTo(admin.getNameEmail().email());
  }

  @Test
  public void createPatchApplyingChange_withConflicts_appendErrorsToCommitMessage()
      throws Exception {
    createBranch(BranchNameKey.create(project, "other"));
    PushOneCommit push =
        pushFactory.create(
            admin.newIdent(),
            testRepo,
            "Adding unexpected base content, which will cause errors",
            PATCH_FILE_NAME,
            "unexpected base content");
    Result conflictingChange = push.to("refs/heads/other");
    conflictingChange.assertOkStatus();
    ChangeInput input = newPatchApplyingChangeInput("other", MODIFICATION_PATCH_INPUT);

    ChangeInfo info = assertCreateSucceeds(input);

    assertThat(info.revisions.get(info.currentRevision).commit.message).contains("errors occurred");
  }

  @Test
  public void createChangeWithCommitTreeSupplier_success() throws Exception {
    createBranch(BranchNameKey.create(project, "other"));
    ChangeInput input = newChangeInput(ChangeStatus.NEW);
    input.branch = "other";
    input.subject = "custom commit message";
    ApplyPatchInput applyPatchInput = new ApplyPatchInput();
    applyPatchInput.patch = PATCH_INPUT;
    CommitTreeSupplier commitTreeSupplier =
        (repo, oi, or, in, mergeTip) ->
            ApplyPatchUtil.applyPatch(repo, oi, applyPatchInput, mergeTip).getTreeId();

    ChangeInfo info = assertCreateWithCommitTreeSupplierSucceeds(input, commitTreeSupplier);

    DiffInfo diff = gApi.changes().id(info.id).current().file(PATCH_FILE_NAME).diff();
    assertDiffForNewFile(diff, info.currentRevision, PATCH_FILE_NAME, PATCH_NEW_FILE_CONTENT);
    assertThat(info.revisions.get(info.currentRevision).commit.message)
        .isEqualTo("custom commit message\n\nChange-Id: " + info.changeId + "\n");
  }

  @Test
  public void changePatch_multipleParents_success() throws Exception {
    changeInTwoBranches("branchA", "a.txt", "branchB", "b.txt");
    ChangeInput in = newMergeChangeInput("branchA", "branchB", "");
    ChangeInfo change = assertCreateSucceeds(in);

    RestResponse patchResp =
        userRestSession.get("/changes/" + change.id + "/revisions/current/patch?parent=1");
    patchResp.assertOK();
    assertThat(new String(Base64.decode(patchResp.getEntityContent()), UTF_8))
        .contains("+B content");

    patchResp = userRestSession.get("/changes/" + change.id + "/revisions/current/patch?parent=2");
    patchResp.assertOK();
    assertThat(new String(Base64.decode(patchResp.getEntityContent()), UTF_8))
        .contains("+A content");
  }

  @Test
  public void changePatch_multipleParents_failure() throws Exception {
    changeInTwoBranches("branchA", "a.txt", "branchB", "b.txt");
    ChangeInput in = newMergeChangeInput("branchA", "branchB", "");
    ChangeInfo change = assertCreateSucceeds(in);

    RestResponse patchResp =
        userRestSession.get("/changes/" + change.id + "/revisions/current/patch");
    // Maintaining historic logic of failing with 409 Conflict in this case.
    patchResp.assertConflict();
  }

  @Test
  public void changePatch_parent_badRequest() throws Exception {
    changeInTwoBranches("branchA", "a.txt", "branchB", "b.txt");
    ChangeInput in = newMergeChangeInput("branchA", "branchB", "");
    ChangeInfo change = assertCreateSucceeds(in);

    RestResponse patchResp =
        userRestSession.get("/changes/" + change.id + "/revisions/current/patch?parent=3");
    // Parent 3 does not exist.
    patchResp.assertBadRequest();

    patchResp = userRestSession.get("/changes/" + change.id + "/revisions/current/patch?parent=0");
    // Parent 0 does not exist.
    patchResp.assertBadRequest();
  }

  @Test
  @UseSystemTime
  public void sha1sOfTwoNewChangesDiffer() throws Exception {
    ChangeInput changeInput = newChangeInput(ChangeStatus.NEW);
    ChangeInfo info1 = assertCreateSucceeds(changeInput);
    ChangeInfo info2 = assertCreateSucceeds(changeInput);
    assertThat(info1.currentRevision).isNotEqualTo(info2.currentRevision);
  }

  @Test
  @UseSystemTime
  public void sha1sOfTwoNewChangesDifferIfCreatedConcurrently() throws Exception {
    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      for (int i = 0; i < 10; i++) {
        ChangeInput changeInput = newChangeInput(ChangeStatus.NEW);

        CyclicBarrier sync = new CyclicBarrier(2);
        Callable<ChangeInfo> createChange =
            () -> {
              requestScopeOperations.setApiUser(admin.id());
              sync.await();
              return assertCreateSucceeds(changeInput);
            };

        Future<ChangeInfo> changeInfo1 = executor.submit(createChange);
        Future<ChangeInfo> changeInfo2 = executor.submit(createChange);
        assertThat(changeInfo1.get().currentRevision)
            .isNotEqualTo(changeInfo2.get().currentRevision);
      }
    }
  }

  @Test
  public void createChangeWithSubmittedMergeSource() throws Exception {
    // Provide coverage for a performance optimization in CommitsCollection#canRead.
    BranchInput branchInput = new BranchInput();
    String mergeTarget = "refs/heads/new-branch";
    RevCommit startCommit = projectOperations.project(project).getHead("master");

    branchInput.revision = startCommit.name();
    branchInput.ref = mergeTarget;

    gApi.projects().name(project.get()).branch(mergeTarget).create(branchInput);

    // To create a merge commit, create two changes from the same parent,
    // and submit them one after the other.
    PushOneCommit.Result result1 =
        pushFactory
            .create(
                admin.newIdent(), testRepo, "subject1", ImmutableMap.of("file1.txt", "content 1"))
            .to("refs/for/master");
    result1.assertOkStatus();

    testRepo.branch("HEAD").update(startCommit);
    PushOneCommit.Result result2 =
        pushFactory
            .create(
                admin.newIdent(), testRepo, "subject2", ImmutableMap.of("file2.txt", "content 2"))
            .to("refs/for/master");
    result2.assertOkStatus();

    ReviewInput reviewInput = ReviewInput.approve().label("Code-Review", 2);

    gApi.changes().id(result1.getChangeId()).revision("current").review(reviewInput);
    gApi.changes().id(result1.getChangeId()).revision("current").submit();

    gApi.changes().id(result2.getChangeId()).revision("current").review(reviewInput);
    gApi.changes().id(result2.getChangeId()).revision("current").submit();

    String mergeRev = gApi.projects().name(project.get()).branch("master").get().revision;
    RevCommit mergeCommit = projectOperations.project(project).getHead("master");
    assertThat(mergeCommit.getParents().length).isEqualTo(2);

    testRepo.git().fetch().call();
    testRepo.branch("HEAD").update(mergeCommit);
    PushOneCommit.Result result3 =
        pushFactory
            .create(
                admin.newIdent(), testRepo, "subject3", ImmutableMap.of("file1.txt", "content 3"))
            .to("refs/for/master");
    result2.assertOkStatus();
    gApi.changes().id(result3.getChangeId()).revision("current").review(reviewInput);
    gApi.changes().id(result3.getChangeId()).revision("current").submit();

    // Now master doesn't point directly to mergeRev
    ChangeInput in = new ChangeInput();
    in.branch = mergeTarget;
    in.merge = new MergeInput();
    in.project = project.get();
    in.merge.source = mergeRev;
    in.subject = "propagate merge";

    gApi.changes().create(in);
  }

  @Test
  public void createChangeWithSourceBranch() throws Exception {
    changeInTwoBranches("branchA", "a.txt", "branchB", "b.txt");

    // create a merge change from branchA to master in gerrit
    ChangeInput in = new ChangeInput();
    in.project = project.get();
    in.branch = "branchA";
    in.subject = "message";
    in.status = ChangeStatus.NEW;
    MergeInput mergeInput = new MergeInput();

    String mergeRev = gApi.projects().name(project.get()).branch("branchB").get().revision;
    mergeInput.source = mergeRev;
    in.merge = mergeInput;

    assertCreateSucceeds(in);

    // Succeeds with a visible branch
    in.merge.sourceBranch = "refs/heads/branchB";
    gApi.changes().create(in);

    // Make it invisible
    projectOperations
        .project(project)
        .forUpdate()
        .add(block(READ).ref(in.merge.sourceBranch).group(REGISTERED_USERS))
        .update();

    // Now it fails.
    assertThrows(BadRequestException.class, () -> gApi.changes().create(in));
  }

  @Test
  public void createChangeWithValidationOptions() throws Exception {
    ChangeInput changeInput = new ChangeInput();
    changeInput.project = project.get();
    changeInput.branch = "master";
    changeInput.subject = "A change";
    changeInput.status = ChangeStatus.NEW;
    changeInput.validationOptions = ImmutableMap.of("key", "value");

    TestCommitValidationListener testCommitValidationListener = new TestCommitValidationListener();
    TestValidationOptionsListener testValidationOptionsListener =
        new TestValidationOptionsListener();
    try (Registration registration =
        extensionRegistry
            .newRegistration()
            .add(testCommitValidationListener)
            .add(testValidationOptionsListener)) {
      assertCreateSucceeds(changeInput);
      assertThat(testCommitValidationListener.receiveEvent.pushOptions)
          .containsExactly("key", "value");
      assertThat(testValidationOptionsListener.validationOptions).containsExactly("key", "value");
    }
  }

  @Test
  public void commitValidationInfoListenerIsInvokedOnChangeCreation() throws Exception {
    ChangeInput changeInput = new ChangeInput();
    changeInput.project = project.get();
    changeInput.branch = "master";
    changeInput.subject = "A change";
    changeInput.status = ChangeStatus.NEW;
    changeInput.validationOptions = ImmutableMap.of("key", "value");

    TestCommitValidationInfoListener testCommitValidationInfoListener =
        new TestCommitValidationInfoListener();
    TestCommitValidationListener testCommitValidationListener = new TestCommitValidationListener();
    try (Registration registration =
        extensionRegistry
            .newRegistration()
            .add(testCommitValidationInfoListener)
            .add(testCommitValidationListener)) {
      ChangeInfo changeInfo = assertCreateSucceeds(changeInput);
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
  public void createChangeWithCustomKeyedValues() throws Exception {
    ChangeInput changeInput = new ChangeInput();
    changeInput.project = project.get();
    changeInput.branch = "master";
    changeInput.subject = "A change";
    changeInput.status = ChangeStatus.NEW;
    changeInput.customKeyedValues = ImmutableMap.of("key", "value");

    ChangeInfo result = assertCreateSucceeds(changeInput);
    assertThat(result.customKeyedValues).containsExactly("key", "value");
  }

  private ChangeInput newChangeInput(ChangeStatus status) {
    ChangeInput in = new ChangeInput();
    in.project = project.get();
    in.branch = "master";
    in.subject = "Empty change";
    in.topic = "support-gerrit-workflow-in-browser";
    in.status = status;
    return in;
  }

  private ChangeInput newChangeWithTopic(String topic) {
    ChangeInput in = newChangeInput(ChangeStatus.NEW);
    in.topic = topic;
    return in;
  }

  private ChangeInfo assertCreateSucceeds(ChangeInput in) throws Exception {
    ChangeInfo out = gApi.changes().create(in).get();
    validateCreateSucceeds(in, out);
    return out;
  }

  private ChangeInfo assertCreateSucceedsUsingRest(ChangeInput in) throws Exception {
    RestResponse resp = adminRestSession.post("/changes/", in);
    resp.assertCreated();
    ChangeInfo res = readContentFromJson(resp, ChangeInfo.class);
    // The original result doesn't contain any revision data.
    ChangeInfo out = gApi.changes().id(res.changeId).get(ALL_REVISIONS, CURRENT_COMMIT);
    validateCreateSucceeds(in, out);
    return out;
  }

  private ChangeInfo assertCreateWithCommitTreeSupplierSucceeds(
      ChangeInput input, CommitTreeSupplier commitTreeSupplier) throws Exception {
    ChangeInfo res =
        createChangeImpl
            .execute(updateFactory, CHANGE_INPUT_PROTO_CONVERTER.toProto(input), commitTreeSupplier)
            .value();
    // The original result doesn't contain any revision data.
    ChangeInfo out = gApi.changes().id(res.changeId).get(ALL_REVISIONS, CURRENT_COMMIT);
    validateCreateSucceeds(input, out);
    return out;
  }

  private static <T> T readContentFromJson(RestResponse r, Class<T> clazz) throws Exception {
    try (JsonReader jsonReader = new JsonReader(r.getReader())) {
      return newGson().fromJson(jsonReader, clazz);
    }
  }

  private void validateCreateSucceeds(ChangeInput in, ChangeInfo out) throws Exception {
    assertThat(out.project).isEqualTo(in.project);
    assertThat(RefNames.fullName(out.branch)).isEqualTo(RefNames.fullName(in.branch));
    assertThat(out.subject).isEqualTo(Splitter.on("\n").splitToList(in.subject).get(0));
    assertThat(out.topic).isEqualTo(in.topic);
    assertThat(out.status).isEqualTo(in.status);
    if (Boolean.TRUE.equals(in.isPrivate)) {
      assertThat(out.isPrivate).isTrue();
    } else {
      assertThat(out.isPrivate).isNull();
    }
    if (Boolean.TRUE.equals(in.workInProgress)) {
      assertThat(out.workInProgress).isTrue();
    } else {
      assertThat(out.workInProgress).isNull();
    }
    assertThat(out.revisions).hasSize(1);
    assertThat(out.submitted).isNull();
    assertThat(out.containsGitConflicts).isNull();
    assertThat(in.status).isEqualTo(ChangeStatus.NEW);
  }

  private ChangeInfo assertCreateSucceedsWithConflicts(ChangeInput in) throws Exception {
    ChangeInfo out = gApi.changes().createAsInfo(in);
    assertThat(out.project).isEqualTo(in.project);
    assertThat(RefNames.fullName(out.branch)).isEqualTo(RefNames.fullName(in.branch));
    assertThat(out.subject).isEqualTo(Splitter.on("\n").splitToList(in.subject).get(0));
    assertThat(out.topic).isEqualTo(in.topic);
    assertThat(out.status).isEqualTo(in.status);
    if (in.isPrivate) {
      assertThat(out.isPrivate).isTrue();
    } else {
      assertThat(out.isPrivate).isNull();
    }
    assertThat(out.submitted).isNull();
    assertThat(out.containsGitConflicts).isTrue();
    assertThat(out.workInProgress).isTrue();
    assertThat(in.status).isEqualTo(ChangeStatus.NEW);
    return out;
  }

  private void assertCreateFails(
      ChangeInput in, Class<? extends RestApiException> errType, String errSubstring)
      throws Exception {
    Throwable thrown = assertThrows(errType, () -> gApi.changes().create(in));
    assertThat(thrown).hasMessageThat().contains(errSubstring);
  }

  // TODO(davido): Expose setting of account preferences in the API
  private void setSignedOffByFooter(boolean value) throws Exception {
    RestResponse r = adminRestSession.get("/accounts/" + admin.email() + "/preferences");
    r.assertOK();
    GeneralPreferencesInfo i = newGson().fromJson(r.getReader(), GeneralPreferencesInfo.class);
    i.signedOffBy = value;

    r = adminRestSession.put("/accounts/" + admin.email() + "/preferences", i);
    r.assertOK();
    GeneralPreferencesInfo o = newGson().fromJson(r.getReader(), GeneralPreferencesInfo.class);

    if (value) {
      assertThat(o.signedOffBy).isTrue();
    } else {
      assertThat(o.signedOffBy).isNull();
    }

    requestScopeOperations.resetCurrentApiUser();
  }

  private ChangeInput newMergeChangeInput(String targetBranch, String sourceRef, String strategy) {
    return newMergeChangeInput(
        project, targetBranch, sourceRef, strategy, /* allowConflicts= */ false);
  }

  private ChangeInput newMergeChangeInput(
      String targetBranch, String sourceRef, String strategy, boolean allowConflicts) {
    return newMergeChangeInput(project, targetBranch, sourceRef, strategy, allowConflicts);
  }

  private ChangeInput newMergeChangeInput(
      Project.NameKey projectName,
      String targetBranch,
      String sourceRef,
      String strategy,
      boolean allowConflicts) {
    // create a merge change from branchA to master in gerrit
    ChangeInput in = new ChangeInput();
    in.project = projectName.get();
    in.branch = targetBranch;
    in.subject = "merge " + sourceRef + " to " + targetBranch;
    in.status = ChangeStatus.NEW;
    MergeInput mergeInput = new MergeInput();
    mergeInput.source = sourceRef;
    in.merge = mergeInput;
    if (!Strings.isNullOrEmpty(strategy)) {
      in.merge.strategy = strategy;
    }
    in.merge.allowConflicts = allowConflicts;
    return in;
  }

  private ChangeInput newPatchApplyingChangeInput(String targetBranch, String patch) {
    // create a change applying the given patch on the target branch in gerrit
    ChangeInput in = new ChangeInput();
    in.project = project.get();
    in.branch = targetBranch;
    in.subject = "apply patch to " + targetBranch;
    in.status = ChangeStatus.NEW;
    ApplyPatchInput patchInput = new ApplyPatchInput();
    patchInput.patch = patch;
    in.patch = patchInput;
    return in;
  }

  /**
   * Create an empty commit in master, two new branches with one commit each.
   *
   * @param branchA name of first branch to create
   * @param fileA name of file to commit to branchA
   * @param branchB name of second branch to create
   * @param fileB name of file to commit to branchB
   * @return A {@code Map} of branchName => commit result.
   */
  private ImmutableMap<String, Result> changeInTwoBranches(
      String branchA, String fileA, String branchB, String fileB) throws Exception {
    return changeInTwoBranches(
        branchA, "change A", fileA, "A content", branchB, "change B", fileB, "B content");
  }

  /**
   * Create an empty commit in master, two new branches with one commit each.
   *
   * @param branchA name of first branch to create
   * @param subjectA commit message subject for the change on branchA
   * @param fileA name of file to commit to branchA
   * @param contentA file content to commit to branchA
   * @param branchB name of second branch to create
   * @param subjectB commit message subject for the change on branchB
   * @param fileB name of file to commit to branchB
   * @param contentB file content to commit to branchB
   * @return A {@code Map} of branchName => commit result.
   */
  private ImmutableMap<String, Result> changeInTwoBranches(
      String branchA,
      String subjectA,
      String fileA,
      String contentA,
      String branchB,
      String subjectB,
      String fileB,
      String contentB)
      throws Exception {
    // create a initial commit in master
    Result initialCommit =
        pushFactory
            .create(user.newIdent(), testRepo, "initial commit", "readme.txt", "initial commit")
            .to("refs/heads/master");
    initialCommit.assertOkStatus();

    // create two new branches
    createBranch(BranchNameKey.create(project, branchA));
    createBranch(BranchNameKey.create(project, branchB));

    // create a commit in branchA
    Result changeA =
        pushFactory
            .create(user.newIdent(), testRepo, subjectA, fileA, contentA)
            .to("refs/heads/" + branchA);
    changeA.assertOkStatus();

    // create a commit in branchB
    PushOneCommit commitB =
        pushFactory.create(user.newIdent(), testRepo, subjectB, fileB, contentB);
    commitB.setParent(initialCommit.getCommit());
    Result changeB = commitB.to("refs/heads/" + branchB);
    changeB.assertOkStatus();

    return ImmutableMap.of("master", initialCommit, branchA, changeA, branchB, changeB);
  }
}
