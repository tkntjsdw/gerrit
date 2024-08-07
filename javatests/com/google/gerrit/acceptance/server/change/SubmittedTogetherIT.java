// Copyright (C) 2015 The Android Open Source Project
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

package com.google.gerrit.acceptance.server.change;

import static com.google.common.truth.Truth.assertThat;
import static com.google.gerrit.acceptance.GitUtil.pushHead;
import static com.google.gerrit.acceptance.testsuite.project.TestProjectUpdate.allow;
import static com.google.gerrit.acceptance.testsuite.project.TestProjectUpdate.block;
import static com.google.gerrit.extensions.api.changes.SubmittedTogetherOption.NON_VISIBLE_CHANGES;
import static com.google.gerrit.server.group.SystemGroupBackend.REGISTERED_USERS;
import static com.google.gerrit.testing.GerritJUnit.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.gerrit.acceptance.AbstractDaemonTest;
import com.google.gerrit.acceptance.GitUtil;
import com.google.gerrit.acceptance.Sandboxed;
import com.google.gerrit.acceptance.TestAccount;
import com.google.gerrit.acceptance.TestProjectInput;
import com.google.gerrit.acceptance.config.GerritConfig;
import com.google.gerrit.acceptance.testsuite.project.ProjectOperations;
import com.google.gerrit.acceptance.testsuite.request.RequestScopeOperations;
import com.google.gerrit.entities.AccountGroup;
import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.entities.Permission;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.api.changes.SubmitInput;
import com.google.gerrit.extensions.api.changes.SubmittedTogetherInfo;
import com.google.gerrit.extensions.api.groups.GroupInput;
import com.google.gerrit.extensions.client.ChangeStatus;
import com.google.gerrit.extensions.client.ListChangesOption;
import com.google.gerrit.extensions.client.SubmitType;
import com.google.gerrit.extensions.common.FileInfo;
import com.google.gerrit.extensions.common.GroupInfo;
import com.google.gerrit.extensions.common.RevisionInfo;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.testing.ConfigSuite;
import com.google.inject.Inject;
import java.util.EnumSet;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.Test;

public class SubmittedTogetherIT extends AbstractDaemonTest {
  @ConfigSuite.Config
  public static Config submitWholeTopicEnabled() {
    return submitWholeTopicEnabledConfig();
  }

  @Inject private ProjectOperations projectOperations;
  @Inject private RequestScopeOperations requestScopeOperations;

  @Test
  public void doesNotIncludeCurrentFiles() throws Exception {
    RevCommit c1_1 = commitBuilder().add("a.txt", "1").message("subject: 1").create();
    RevCommit c2_1 = commitBuilder().add("b.txt", "2").message("subject: 2").create();
    String id2 = getChangeId(c2_1);
    pushHead(testRepo, "refs/for/master", false);

    SubmittedTogetherInfo info =
        gApi.changes().id(id2).submittedTogether(EnumSet.of(NON_VISIBLE_CHANGES));
    assertThat(info.changes).hasSize(2);
    assertThat(info.changes.get(0).currentRevision).isEqualTo(c2_1.name());
    assertThat(info.changes.get(1).currentRevision).isEqualTo(c1_1.name());

    RevisionInfo rev = info.changes.get(0).revisions.get(c2_1.name());
    assertThat(rev.files).isNull();
  }

  @Test
  public void returnsCurrentFilesIfOptionRequested() throws Exception {
    RevCommit c1_1 = commitBuilder().add("a.txt", "1").message("subject: 1").create();
    RevCommit c2_1 = commitBuilder().add("b.txt", "2").message("subject: 2").create();
    String id2 = getChangeId(c2_1);
    pushHead(testRepo, "refs/for/master", false);

    SubmittedTogetherInfo info =
        gApi.changes()
            .id(id2)
            .submittedTogether(
                EnumSet.of(ListChangesOption.CURRENT_FILES), EnumSet.of(NON_VISIBLE_CHANGES));
    assertThat(info.changes).hasSize(2);
    assertThat(info.changes.get(0).currentRevision).isEqualTo(c2_1.name());
    assertThat(info.changes.get(1).currentRevision).isEqualTo(c1_1.name());

    RevisionInfo rev = info.changes.get(0).revisions.get(c2_1.name());
    assertThat(rev).isNotNull();
    FileInfo file = rev.files.get("b.txt");
    assertThat(file).isNotNull();
    assertThat(file.status).isEqualTo('A');
  }

  @Test
  public void returnsAncestors() throws Exception {
    // Create two commits and push.
    RevCommit c1_1 = commitBuilder().add("a.txt", "1").message("subject: 1").create();
    String id1 = getChangeId(c1_1);
    RevCommit c2_1 = commitBuilder().add("b.txt", "2").message("subject: 2").create();
    String id2 = getChangeId(c2_1);
    pushHead(testRepo, "refs/for/master", false);

    assertSubmittedTogether(id1);
    assertSubmittedTogether(id2, id2, id1);
  }

  @Test
  public void anonymousAncestors() throws Exception {
    RevCommit a = commitBuilder().add("a", "1").message("change 1").create();
    RevCommit b = commitBuilder().add("b", "1").message("change 2").create();
    pushHead(testRepo, "refs/for/master", false);

    requestScopeOperations.setApiUserAnonymous();
    assertSubmittedTogether(getChangeId(a));
    assertSubmittedTogether(getChangeId(b), getChangeId(b), getChangeId(a));
  }

  @Test
  public void respectWholeTopic() throws Exception {
    RevCommit initialHead = projectOperations.project(project).getHead("master");
    // Create two independent commits and push.
    RevCommit c1_1 = commitBuilder().add("a.txt", "1").message("subject: 1").create();
    String id1 = getChangeId(c1_1);
    pushHead(testRepo, "refs/for/master%topic=" + name("connectingTopic"), false);

    testRepo.reset(initialHead);
    RevCommit c2_1 = commitBuilder().add("b.txt", "2").message("subject: 2").create();
    String id2 = getChangeId(c2_1);
    pushHead(testRepo, "refs/for/master%topic=" + name("connectingTopic"), false);

    if (isSubmitWholeTopicEnabled()) {
      assertSubmittedTogether(id1, id2, id1);
      assertSubmittedTogether(id2, id2, id1);
    } else {
      assertSubmittedTogether(id1);
      assertSubmittedTogether(id2);
      assertSubmittedTogetherWithTopicClosure(id1, id2, id1);
      assertSubmittedTogetherWithTopicClosure(id2, id2, id1);
    }
  }

  @Test
  public void anonymousWholeTopic() throws Exception {
    RevCommit initialHead = projectOperations.project(project).getHead("master");
    RevCommit a = commitBuilder().add("a", "1").message("change 1").create();
    pushHead(testRepo, "refs/for/master%topic=" + name("topic"), false);
    String id1 = getChangeId(a);

    testRepo.reset(initialHead);
    RevCommit b = commitBuilder().add("b", "1").message("change 2").create();
    pushHead(testRepo, "refs/for/master%topic=" + name("topic"), false);
    String id2 = getChangeId(b);

    requestScopeOperations.setApiUserAnonymous();
    if (isSubmitWholeTopicEnabled()) {
      assertSubmittedTogether(id1, id2, id1);
      assertSubmittedTogether(id2, id2, id1);
    } else {
      assertSubmittedTogether(id1);
      assertSubmittedTogether(id2);
      assertSubmittedTogetherWithTopicClosure(id1, id2, id1);
      assertSubmittedTogetherWithTopicClosure(id2, id2, id1);
    }
  }

  @Test
  public void topicChaining() throws Exception {
    RevCommit initialHead = projectOperations.project(project).getHead("master");

    RevCommit c1_1 = commitBuilder().add("a.txt", "1").message("subject: 1").create();
    String id1 = getChangeId(c1_1);
    pushHead(testRepo, "refs/for/master%topic=" + name("connectingTopic"), false);

    testRepo.reset(initialHead);
    RevCommit c2_1 = commitBuilder().add("b.txt", "2").message("subject: 2").create();
    String id2 = getChangeId(c2_1);
    pushHead(testRepo, "refs/for/master%topic=" + name("connectingTopic"), false);

    RevCommit c3_1 = commitBuilder().add("b.txt", "3").message("subject: 3").create();
    String id3 = getChangeId(c3_1);
    pushHead(testRepo, "refs/for/master%topic=" + name("unrelated-topic"), false);

    if (isSubmitWholeTopicEnabled()) {
      assertSubmittedTogether(id1, id2, id1);
      assertSubmittedTogether(id2, id2, id1);
      assertSubmittedTogether(id3, id3, id2, id1);
    } else {
      assertSubmittedTogether(id1);
      assertSubmittedTogether(id2);
      assertSubmittedTogether(id3, id3, id2);
      assertSubmittedTogetherWithTopicClosure(id1, id2, id1);
      assertSubmittedTogetherWithTopicClosure(id2, id2, id1);
      assertSubmittedTogetherWithTopicClosure(id3, id3, id2, id1);
    }
  }

  @Test
  @Sandboxed
  @GerritConfig(name = "change.maxSubmittableAtOnce", value = "2")
  public void submittedTogetherWithMaxChangesLimit() throws Exception {
    String targetRef = "refs/for/master";

    commitBuilder().add("a.txt", "1").message("subject: 1").create();
    pushHead(testRepo, targetRef, false);

    RevCommit c2_1 = commitBuilder().add("b.txt", "2").message("subject: 2").create();
    String id2 = getChangeId(c2_1);
    pushHead(testRepo, targetRef, false);

    RevCommit c3_1 = commitBuilder().add("b.txt", "3").message("subject: 3").create();
    String id3 = getChangeId(c3_1);
    pushHead(testRepo, targetRef, false);

    assertSubmittedTogether(id3, id3, id2);
  }

  @Test
  public void respectTopicsOnAncestors() throws Exception {
    RevCommit initialHead = projectOperations.project(project).getHead("master");

    RevCommit c1_1 = commitBuilder().add("a.txt", "1").message("subject: 1").create();
    String id1 = getChangeId(c1_1);
    pushHead(testRepo, "refs/for/master%topic=" + name("connectingTopic"), false);

    testRepo.reset(initialHead);
    RevCommit c2_1 = commitBuilder().add("b.txt", "2").message("subject: 2").create();
    String id2 = getChangeId(c2_1);
    pushHead(testRepo, "refs/for/master%topic=" + name("otherConnectingTopic"), false);

    RevCommit c3_1 = commitBuilder().add("b.txt", "3").message("subject: 3").create();
    String id3 = getChangeId(c3_1);
    pushHead(testRepo, "refs/for/master%topic=" + name("connectingTopic"), false);

    RevCommit c4_1 = commitBuilder().add("b.txt", "4").message("subject: 4").create();
    String id4 = getChangeId(c4_1);
    pushHead(testRepo, "refs/for/master", false);

    testRepo.reset(initialHead);
    RevCommit c5_1 = commitBuilder().add("c.txt", "5").message("subject: 5").create();
    String id5 = getChangeId(c5_1);
    pushHead(testRepo, "refs/for/master", false);

    RevCommit c6_1 = commitBuilder().add("c.txt", "6").message("subject: 6").create();
    String id6 = getChangeId(c6_1);
    pushHead(testRepo, "refs/for/master%topic=" + name("otherConnectingTopic"), false);

    if (isSubmitWholeTopicEnabled()) {
      assertSubmittedTogether(id1, id6, id5, id3, id2, id1);
      assertSubmittedTogether(id2, id6, id5, id2);
      assertSubmittedTogether(id3, id6, id5, id3, id2, id1);
      assertSubmittedTogether(id4, id6, id5, id4, id3, id2, id1);
      assertSubmittedTogether(id5);
      assertSubmittedTogether(id6, id6, id5, id2);
    } else {
      assertSubmittedTogether(id1);
      assertSubmittedTogether(id2);
      assertSubmittedTogether(id3, id3, id2);
      assertSubmittedTogether(id4, id4, id3, id2);
      assertSubmittedTogether(id5);
      assertSubmittedTogether(id6, id6, id5);

      assertSubmittedTogetherWithTopicClosure(id1, id6, id5, id3, id2, id1);
      assertSubmittedTogetherWithTopicClosure(id2, id6, id5, id2);
      assertSubmittedTogetherWithTopicClosure(id3, id6, id5, id3, id2, id1);
      assertSubmittedTogetherWithTopicClosure(id4, id6, id5, id4, id3, id2, id1);
      assertSubmittedTogetherWithTopicClosure(id5);
      assertSubmittedTogetherWithTopicClosure(id6, id6, id5, id2);
    }
  }

  @Test
  public void newBranchTwoChangesTogether() throws Exception {
    Project.NameKey p1 = projectOperations.newProject().noEmptyCommit().create();

    TestRepository<?> repo1 = cloneProject(p1);

    RevCommit c1 =
        repo1
            .branch("HEAD")
            .commit()
            .insertChangeId()
            .add("a.txt", "1")
            .message("subject: 1")
            .create();
    String id1 = GitUtil.getChangeId(repo1, c1).get();
    pushHead(repo1, "refs/for/master", false);

    RevCommit c2 =
        repo1
            .branch("HEAD")
            .commit()
            .insertChangeId()
            .add("b.txt", "2")
            .message("subject: 2")
            .create();
    String id2 = GitUtil.getChangeId(repo1, c2).get();
    pushHead(repo1, "refs/for/master", false);
    assertSubmittedTogether(id1);
    assertSubmittedTogether(id2, id2, id1);
  }

  @Test
  @TestProjectInput(submitType = SubmitType.CHERRY_PICK)
  public void testCherryPickWithoutAncestors() throws Exception {
    // Create two commits and push.
    RevCommit c1_1 = commitBuilder().add("a.txt", "1").message("subject: 1").create();
    String id1 = getChangeId(c1_1);
    RevCommit c2_1 = commitBuilder().add("b.txt", "2").message("subject: 2").create();
    String id2 = getChangeId(c2_1);
    pushHead(testRepo, "refs/for/master", false);

    assertSubmittedTogether(id1);
    assertSubmittedTogether(id2);
  }

  @Test
  public void submissionIdSavedOnMergeInOneProject() throws Exception {
    // Create two commits and push.
    RevCommit c1_1 = commitBuilder().add("a.txt", "1").message("subject: 1").create();
    String id1 = getChangeId(c1_1);
    RevCommit c2_1 = commitBuilder().add("b.txt", "2").message("subject: 2").create();
    String id2 = getChangeId(c2_1);
    pushHead(testRepo, "refs/for/master", false);

    assertSubmittedTogether(id1);
    assertSubmittedTogether(id2, id2, id1);

    approve(id1);
    approve(id2);
    submit(id2);
    assertMerged(id1);
    assertMerged(id2);

    // Prior to submission this was empty, but the post-merge value is what was
    // actually submitted.
    assertSubmittedTogether(id1, id2, id1);

    assertSubmittedTogether(id2, id2, id1);
  }

  @Test
  public void permissionToSubmitForSomeChangesInTopic() throws Exception {
    projectOperations
        .project(project)
        .forUpdate()
        .add(block(Permission.SUBMIT).ref("refs/heads/testbranch").group(REGISTERED_USERS))
        .update();

    createBranch(BranchNameKey.create(getProject(), "testbranch"));
    RevCommit initialHead = projectOperations.project(project).getHead("master");
    // Create two independent commits and push.
    RevCommit c1_1 = commitBuilder().add("a.txt", "1").message("subject: 1").create();
    String id1 = getChangeId(c1_1);
    pushHead(testRepo, "refs/for/master%topic=" + name("connectingTopic"), false);

    testRepo.reset(initialHead);
    RevCommit c2_1 = commitBuilder().add("b.txt", "2").message("subject: 2").create();
    String id2 = getChangeId(c2_1);
    pushHead(testRepo, "refs/for/testbranch%topic=" + name("connectingTopic"), false);

    approve(id1);
    approve(id2);
    if (isSubmitWholeTopicEnabled()) {
      ResourceConflictException e =
          assertThrows(ResourceConflictException.class, () -> submit(id1));
      assertThat(e.getMessage())
          .contains(
              String.format(
                  "Insufficient permission to submit change %d",
                  gApi.changes().id(id2).get()._number));
    } else {
      submit(id1);
    }
  }

  @Test
  public void permissionToSubmitAsForSomeChangesInTopic() throws Exception {
    projectOperations
        .project(project)
        .forUpdate()
        .add(allow(Permission.SUBMIT_AS).ref("refs/heads/master").group(REGISTERED_USERS))
        .add(block(Permission.SUBMIT_AS).ref("refs/heads/testbranch").group(REGISTERED_USERS))
        .update();

    createBranch(BranchNameKey.create(getProject(), "testbranch"));
    RevCommit initialHead = projectOperations.project(project).getHead("master");
    // Create two independent commits and push.
    RevCommit c1_1 = commitBuilder().add("a.txt", "1").message("subject: 1").create();
    String id1 = getChangeId(c1_1);
    pushHead(testRepo, "refs/for/master%topic=" + name("connectingTopic"), false);

    testRepo.reset(initialHead);
    RevCommit c2_1 = commitBuilder().add("b.txt", "2").message("subject: 2").create();
    String id2 = getChangeId(c2_1);
    pushHead(testRepo, "refs/for/testbranch%topic=" + name("connectingTopic"), false);

    approve(id1);
    approve(id2);
    SubmitInput in = new SubmitInput();
    in.onBehalfOf = accountCreator.user2().email();
    if (isSubmitWholeTopicEnabled()) {
      ResourceConflictException e =
          assertThrows(
              ResourceConflictException.class, () -> gApi.changes().id(id1).current().submit(in));
      assertThat(e.getMessage())
          .contains(
              String.format(
                  "Insufficient permission to submit change %d on behalf of user %s",
                  gApi.changes().id(id2).get()._number, accountCreator.user2().username()));
    } else {
      gApi.changes().id(id1).current().submit(in);
      assertMerged(id1);
      assertNotMerged(id2);
    }
  }

  @Test
  public void submitAs_onBehalfOfUserNoReadPermissionToSomeChanges() throws Exception {
    GroupInfo newGroup = createGroupForUser(accountCreator.user2());
    projectOperations
        .project(project)
        .forUpdate()
        .add(
            block(Permission.READ)
                .ref("refs/heads/testbranch")
                .group(AccountGroup.uuid(newGroup.id)))
        .add(allow(Permission.SUBMIT_AS).ref("refs/heads/*").group(REGISTERED_USERS))
        .update();

    createBranch(BranchNameKey.create(getProject(), "testbranch"));
    RevCommit initialHead = projectOperations.project(project).getHead("master");
    // Create two independent commits and push.
    RevCommit c1_1 = commitBuilder().add("a.txt", "1").message("subject: 1").create();
    String id1 = getChangeId(c1_1);
    pushHead(testRepo, "refs/for/master%topic=" + name("connectingTopic"), false);

    testRepo.reset(initialHead);
    RevCommit c2_1 = commitBuilder().add("b.txt", "2").message("subject: 2").create();
    String id2 = getChangeId(c2_1);
    pushHead(testRepo, "refs/for/testbranch%topic=" + name("connectingTopic"), false);

    approve(id1);
    approve(id2);
    SubmitInput in = new SubmitInput();
    in.onBehalfOf = accountCreator.user2().email();
    if (isSubmitWholeTopicEnabled()) {
      ResourceConflictException e =
          assertThrows(
              ResourceConflictException.class, () -> gApi.changes().id(id1).current().submit(in));
      assertThat(e.getMessage())
          .contains(
              String.format(
                  "On-behalf-of user %s lacks permission to read change %d",
                  accountCreator.user2().username(), gApi.changes().id(id2).get()._number));
    } else {
      gApi.changes().id(id1).current().submit(in);
      assertMerged(id1);
      assertNotMerged(id2);
    }
  }

  @Test
  public void submitAs_succeeds() throws Exception {
    GroupInfo newGroup = createGroupForUser(accountCreator.user2());
    projectOperations
        .project(project)
        .forUpdate()
        .add(
            block(Permission.SUBMIT)
                .ref("refs/heads/testbranch")
                .group(AccountGroup.uuid(newGroup.id)))
        .add(allow(Permission.SUBMIT_AS).ref("refs/heads/*").group(REGISTERED_USERS))
        .update();

    createBranch(BranchNameKey.create(getProject(), "testbranch"));
    RevCommit initialHead = projectOperations.project(project).getHead("master");
    // Create two independent commits and push.
    RevCommit c1_1 = commitBuilder().add("a.txt", "1").message("subject: 1").create();
    String id1 = getChangeId(c1_1);
    pushHead(testRepo, "refs/for/master%topic=" + name("connectingTopic"), false);

    testRepo.reset(initialHead);
    RevCommit c2_1 = commitBuilder().add("b.txt", "2").message("subject: 2").create();
    String id2 = getChangeId(c2_1);
    pushHead(testRepo, "refs/for/testbranch%topic=" + name("connectingTopic"), false);

    approve(id1);
    approve(id2);
    SubmitInput in = new SubmitInput();
    in.onBehalfOf = accountCreator.user2().email();
    if (isSubmitWholeTopicEnabled()) {
      gApi.changes().id(id1).current().submit(in);
      assertMerged(id1);
      assertMerged(id2);
    } else {
      gApi.changes().id(id1).current().submit(in);
      assertMerged(id1);
      assertNotMerged(id2);
    }
  }

  private GroupInfo createGroupForUser(TestAccount user) throws Exception {
    GroupInput gi = new GroupInput();
    gi.name = name("New-Group");
    gi.members = ImmutableList.of(user.id().toString());
    return gApi.groups().create(gi).get();
  }

  private String getChangeId(RevCommit c) throws Exception {
    return GitUtil.getChangeId(testRepo, c).get();
  }

  private void submit(String changeId) throws Exception {
    gApi.changes().id(changeId).current().submit();
  }

  private void assertMerged(String changeId) throws Exception {
    assertThat(gApi.changes().id(changeId).get().status).isEqualTo(ChangeStatus.MERGED);
  }

  private void assertNotMerged(String changeId) throws Exception {
    assertThat(gApi.changes().id(changeId).get().status).isEqualTo(ChangeStatus.NEW);
  }
}
