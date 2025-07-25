// Copyright (C) 2018 The Android Open Source Project
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

package com.google.gerrit.server.change;

import static com.google.common.base.Preconditions.checkState;
import static com.google.gerrit.extensions.client.ListChangesOption.ALL_COMMITS;
import static com.google.gerrit.extensions.client.ListChangesOption.ALL_FILES;
import static com.google.gerrit.extensions.client.ListChangesOption.ALL_REVISIONS;
import static com.google.gerrit.extensions.client.ListChangesOption.COMMIT_FOOTERS;
import static com.google.gerrit.extensions.client.ListChangesOption.CURRENT_ACTIONS;
import static com.google.gerrit.extensions.client.ListChangesOption.CURRENT_COMMIT;
import static com.google.gerrit.extensions.client.ListChangesOption.CURRENT_FILES;
import static com.google.gerrit.extensions.client.ListChangesOption.DETAILED_ACCOUNTS;
import static com.google.gerrit.extensions.client.ListChangesOption.DOWNLOAD_COMMANDS;
import static com.google.gerrit.extensions.client.ListChangesOption.PARENTS;
import static com.google.gerrit.extensions.client.ListChangesOption.PUSH_CERTIFICATES;
import static com.google.gerrit.extensions.client.ListChangesOption.WEB_LINKS;
import static com.google.gerrit.server.CommonConverters.toGitPerson;
import static com.google.gerrit.server.project.ProjectCache.illegalState;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.ParentCommitData;
import com.google.gerrit.entities.Patch;
import com.google.gerrit.entities.PatchSet;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.client.ListChangesOption;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.extensions.common.CommitInfo;
import com.google.gerrit.extensions.common.ConflictsInfo;
import com.google.gerrit.extensions.common.FetchInfo;
import com.google.gerrit.extensions.common.PushCertificateInfo;
import com.google.gerrit.extensions.common.RevisionInfo;
import com.google.gerrit.extensions.common.RevisionInfo.ParentInfo;
import com.google.gerrit.extensions.common.WebLinkInfo;
import com.google.gerrit.extensions.config.DownloadCommand;
import com.google.gerrit.extensions.config.DownloadScheme;
import com.google.gerrit.extensions.registration.DynamicMap;
import com.google.gerrit.extensions.registration.Extension;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.metrics.Description;
import com.google.gerrit.metrics.Description.Units;
import com.google.gerrit.metrics.MetricMaker;
import com.google.gerrit.metrics.Timer0;
import com.google.gerrit.server.AnonymousUser;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.GpgException;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.WebLinks;
import com.google.gerrit.server.account.AccountLoader;
import com.google.gerrit.server.account.GpgApiAdapter;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.MergeUtilFactory;
import com.google.gerrit.server.logging.Metadata;
import com.google.gerrit.server.logging.TraceContext;
import com.google.gerrit.server.logging.TraceContext.TraceTimer;
import com.google.gerrit.server.patch.PatchListNotAvailableException;
import com.google.gerrit.server.permissions.ChangePermission;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.project.ProjectState;
import com.google.gerrit.server.query.change.ChangeData;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import com.google.inject.assistedinject.Assisted;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.jgit.attributes.AttributesNodeProvider;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

/** Produces {@link RevisionInfo} and {@link CommitInfo} which are serialized to JSON afterwards. */
public class RevisionJson {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  public interface Factory {
    RevisionJson create(Iterable<ListChangesOption> options);
  }

  @Singleton
  private static class Metrics {
    private final Timer0 parentDataLatency;

    @Inject
    Metrics(MetricMaker metricMaker) {
      parentDataLatency =
          metricMaker.newTimer(
              "http/server/rest_api/change_json/to_change_info_latency/parent_data_computation",
              new Description(
                      "Latency for computing parent data information in toRevisionInfo"
                          + " invocations in RevisionJson")
                  .setCumulative()
                  .setUnit(Units.MILLISECONDS));
    }
  }

  private final MergeUtilFactory mergeUtilFactory;
  private final IdentifiedUser.GenericFactory userFactory;
  private final FileInfoJson fileInfoJson;
  private final GpgApiAdapter gpgApi;
  private final ChangeResource.Factory changeResourceFactory;
  private final ChangeKindCache changeKindCache;
  private final ActionJson actionJson;
  private final DynamicMap<DownloadScheme> downloadSchemes;
  private final DynamicMap<DownloadCommand> downloadCommands;
  private final WebLinks webLinks;
  private final Provider<CurrentUser> userProvider;
  private final ProjectCache projectCache;
  private final ImmutableSet<ListChangesOption> options;
  private final AccountLoader.Factory accountLoaderFactory;
  private final AnonymousUser anonymous;
  private final GitRepositoryManager repoManager;
  private final PermissionBackend permissionBackend;
  private final ParentDataProvider parentDataProvider;
  private final Metrics metrics;

  @Inject
  RevisionJson(
      Provider<CurrentUser> userProvider,
      AnonymousUser anonymous,
      ProjectCache projectCache,
      IdentifiedUser.GenericFactory userFactory,
      MergeUtilFactory mergeUtilFactory,
      FileInfoJson fileInfoJson,
      AccountLoader.Factory accountLoaderFactory,
      DynamicMap<DownloadScheme> downloadSchemes,
      DynamicMap<DownloadCommand> downloadCommands,
      WebLinks webLinks,
      ActionJson actionJson,
      GpgApiAdapter gpgApi,
      ChangeResource.Factory changeResourceFactory,
      ChangeKindCache changeKindCache,
      GitRepositoryManager repoManager,
      PermissionBackend permissionBackend,
      ParentDataProvider parentDataProvider,
      Metrics metrics,
      @Assisted Iterable<ListChangesOption> options) {
    this.userProvider = userProvider;
    this.anonymous = anonymous;
    this.projectCache = projectCache;
    this.userFactory = userFactory;
    this.mergeUtilFactory = mergeUtilFactory;
    this.fileInfoJson = fileInfoJson;
    this.accountLoaderFactory = accountLoaderFactory;
    this.downloadSchemes = downloadSchemes;
    this.downloadCommands = downloadCommands;
    this.webLinks = webLinks;
    this.actionJson = actionJson;
    this.gpgApi = gpgApi;
    this.changeResourceFactory = changeResourceFactory;
    this.changeKindCache = changeKindCache;
    this.permissionBackend = permissionBackend;
    this.repoManager = repoManager;
    this.parentDataProvider = parentDataProvider;
    this.metrics = metrics;
    this.options = ImmutableSet.copyOf(options);
  }

  /**
   * Returns a {@link RevisionInfo} based on a change and patch set. Reads from the repository
   * depending on the options provided when constructing this instance.
   */
  public RevisionInfo getRevisionInfo(ChangeData cd, PatchSet in)
      throws PatchListNotAvailableException, GpgException, IOException, PermissionBackendException {
    AccountLoader accountLoader = accountLoaderFactory.create(has(DETAILED_ACCOUNTS));
    try (Repository repo = openRepoIfNecessary(cd.project());
        RevWalk rw = newRevWalk(repo)) {
      RevisionInfo rev =
          toRevisionInfo(
              accountLoader,
              cd,
              in,
              repo,
              rw,
              true,
              null,
              repo != null ? repo.createAttributesNodeProvider() : null);
      accountLoader.fill();
      return rev;
    }
  }

  /**
   * Returns a {@link CommitInfo} based on a commit and formatting options. Uses the provided
   * RevWalk and assumes it is backed by an open repository.
   */
  public CommitInfo getCommitInfo(
      Project.NameKey project,
      RevWalk rw,
      RevCommit commit,
      boolean addLinks,
      boolean fillCommit,
      String branchName,
      String changeKey,
      int numericChangeId)
      throws IOException {
    CommitInfo info = new CommitInfo();
    if (fillCommit) {
      info.commit = commit.name();
    }
    info.parents = new ArrayList<>(commit.getParentCount());
    info.author = toGitPerson(commit.getAuthorIdent());
    info.committer = toGitPerson(commit.getCommitterIdent());
    info.subject = commit.getShortMessage();
    info.message = commit.getFullMessage();

    if (addLinks) {
      ImmutableList<WebLinkInfo> patchSetLinks =
          webLinks.getPatchSetLinks(
              project,
              commit.name(),
              commit.getFullMessage(),
              branchName,
              changeKey,
              numericChangeId);
      info.webLinks = patchSetLinks.isEmpty() ? null : patchSetLinks;
      ImmutableList<WebLinkInfo> resolveConflictsLinks =
          webLinks.getResolveConflictsLinks(
              project, commit.name(), commit.getFullMessage(), branchName);
      info.resolveConflictsWebLinks =
          resolveConflictsLinks.isEmpty() ? null : resolveConflictsLinks;
    }

    for (RevCommit parent : commit.getParents()) {
      rw.parseBody(parent);
      CommitInfo i = new CommitInfo();
      i.commit = parent.name();
      i.subject = parent.getShortMessage();
      if (addLinks) {
        ImmutableList<WebLinkInfo> parentLinks =
            webLinks.getParentLinks(project, parent.name(), parent.getFullMessage(), branchName);
        i.webLinks = parentLinks.isEmpty() ? null : parentLinks;
      }
      info.parents.add(i);
    }
    return info;
  }

  /**
   * Returns multiple {@link RevisionInfo}s for a single change. Uses the provided {@link
   * AccountLoader} to lazily populate accounts. Callers have to call {@link AccountLoader#fill()}
   * afterwards to populate all accounts in the returned {@link RevisionInfo}s.
   */
  Map<String, RevisionInfo> getRevisions(
      AccountLoader accountLoader,
      ChangeData cd,
      Map<PatchSet.Id, PatchSet> map,
      Optional<PatchSet.Id> limitToPsId,
      ChangeInfo changeInfo)
      throws PatchListNotAvailableException, GpgException, IOException, PermissionBackendException {
    try (TraceTimer timer =
        TraceContext.newTimer(
            "Get revisions", Metadata.builder().changeId(cd.change().getId().get()).build())) {
      Map<String, RevisionInfo> res = new LinkedHashMap<>();
      try (Repository repo = openRepoIfNecessary(cd.project());
          RevWalk rw = newRevWalk(repo)) {
        AttributesNodeProvider attributesNodeProvider =
            repo != null ? repo.createAttributesNodeProvider() : null;
        for (PatchSet in : map.values()) {
          PatchSet.Id id = in.id();
          boolean want;
          if (has(ALL_REVISIONS)) {
            want = true;
          } else if (limitToPsId.isPresent()) {
            want = id.equals(limitToPsId.get());
          } else {
            want = id.equals(cd.change().currentPatchSetId());
          }
          if (want) {
            res.put(
                in.commitId().name(),
                toRevisionInfo(
                    accountLoader, cd, in, repo, rw, false, changeInfo, attributesNodeProvider));
          }
        }
        return res;
      }
    }
  }

  private Map<String, FetchInfo> makeFetchMap(ChangeData cd, PatchSet in)
      throws PermissionBackendException {
    Map<String, FetchInfo> r = new LinkedHashMap<>();
    for (Extension<DownloadScheme> e : downloadSchemes) {
      String schemeName = e.getExportName();
      DownloadScheme scheme = e.getProvider().get();
      if (!scheme.isEnabled()
          || scheme.isHidden()
          || (scheme.isAuthRequired() && !userProvider.get().isIdentifiedUser())) {
        continue;
      }
      if (!scheme.isAuthSupported() && !isWorldReadable(cd)) {
        continue;
      }

      String projectName = cd.project().get();
      String url = scheme.getUrl(projectName);
      String refName = in.refName();
      FetchInfo fetchInfo = new FetchInfo(url, refName);
      r.put(schemeName, fetchInfo);

      if (has(DOWNLOAD_COMMANDS)) {
        DownloadCommandsJson.populateFetchMap(
            scheme, downloadCommands, projectName, refName, fetchInfo);
      }
    }

    return r;
  }

  private RevisionInfo toRevisionInfo(
      AccountLoader accountLoader,
      ChangeData cd,
      PatchSet in,
      @Nullable Repository repo,
      @Nullable RevWalk rw,
      boolean fillCommit,
      @Nullable ChangeInfo changeInfo,
      @Nullable AttributesNodeProvider attributesNodeProvider)
      throws PatchListNotAvailableException, GpgException, IOException, PermissionBackendException {
    Change c = cd.change();
    RevisionInfo out = new RevisionInfo();
    out.isCurrent = in.id().equals(c.currentPatchSetId());
    out._number = in.id().get();
    out.ref = in.refName();
    out.setCreated(in.createdOn());
    if (in.branch().isPresent()) {
      // set the per-patch-set branch if it exists
      out.branch = in.branch().get();
    } else if (in.number() == cd.patchSets().size()) {
      // only set the per-change branch on this patch-set if this is the last patch-set
      out.branch = cd.change().getDest().branch();
    }
    out.uploader = accountLoader.get(in.uploader());
    if (!in.uploader().equals(in.realUploader())) {
      out.realUploader = accountLoader.get(in.realUploader());
    }
    out.fetch = makeFetchMap(cd, in);
    out.kind =
        changeKindCache.getChangeKind(
            rw, repo != null ? repo.getConfig() : null, attributesNodeProvider, cd, in);
    out.description = in.description().orElse(null);
    out.conflicts =
        in.conflicts()
            .map(
                conflicts -> {
                  ConflictsInfo conflictsInfo = new ConflictsInfo();
                  conflictsInfo.containsConflicts = conflicts.containsConflicts();
                  conflictsInfo.base = conflicts.base().map(ObjectId::getName).orElse(null);
                  conflictsInfo.ours = conflicts.ours().map(ObjectId::getName).orElse(null);
                  conflictsInfo.theirs = conflicts.theirs().map(ObjectId::getName).orElse(null);
                  conflictsInfo.mergeStrategy = conflicts.mergeStrategy().orElse(null);
                  conflictsInfo.noBaseReason = conflicts.noBaseReason().orElse(null);
                  return conflictsInfo;
                })
            .orElse(null);

    boolean setCommit = has(ALL_COMMITS) || (out.isCurrent && has(CURRENT_COMMIT));
    boolean addFooters = out.isCurrent && has(COMMIT_FOOTERS);
    if (setCommit || addFooters) {
      checkState(rw != null);
      checkState(repo != null);
      Project.NameKey project = c.getProject();
      String rev = in.commitId().name();
      RevCommit commit = rw.parseCommit(ObjectId.fromString(rev));
      rw.parseBody(commit);
      String branchName = out.branch;
      if (setCommit) {
        out.commit =
            getCommitInfo(
                project,
                rw,
                commit,
                has(WEB_LINKS),
                fillCommit,
                branchName,
                c.getKey().get(),
                c.getId().get());
      }
      if (has(PARENTS)) {
        try (Timer0.Context ignored = metrics.parentDataLatency.start()) {
          String targetBranch =
              in.branch().isPresent() ? in.branch().get() : cd.change().getDest().branch();
          List<ParentCommitData> parentData = new ArrayList<>();
          for (RevCommit parent : commit.getParents()) {
            ParentCommitData p =
                parentDataProvider.get(project, repo, parent.getId(), targetBranch);
            parentData.add(p);
          }
          out.parentsData = getParentInfo(parentData);
        }
      }
      if (addFooters) {
        Ref ref = repo.exactRef(branchName);
        RevCommit mergeTip = null;
        if (ref != null) {
          mergeTip = rw.parseCommit(ref.getObjectId());
          rw.parseBody(mergeTip);
        }
        out.commitWithFooters =
            mergeUtilFactory
                .create(projectCache.get(project).orElseThrow(illegalState(project)))
                .createCommitMessageOnSubmit(commit, mergeTip, cd.notes(), in.id());
      }
    }

    if (has(ALL_FILES) || (out.isCurrent && has(CURRENT_FILES))) {
      try {
        out.files = fileInfoJson.getFileInfoMap(c, in);
        out.files.remove(Patch.COMMIT_MSG);
        out.files.remove(Patch.MERGE_LIST);
      } catch (ResourceConflictException e) {
        logger.atWarning().withCause(e).log("creating file list failed");
      }
    }

    if (out.isCurrent && has(CURRENT_ACTIONS) && userProvider.get().isIdentifiedUser()) {
      actionJson.addRevisionActions(
          changeInfo,
          out,
          new RevisionResource(changeResourceFactory.create(cd, userProvider.get()), in));
    }

    if (gpgApi.isEnabled() && has(PUSH_CERTIFICATES)) {
      if (in.pushCertificate().isPresent()) {
        out.pushCertificate =
            gpgApi.checkPushCertificate(
                in.pushCertificate().get(), userFactory.create(in.uploader()));
      } else {
        out.pushCertificate = new PushCertificateInfo();
      }
    }

    return out;
  }

  private boolean has(ListChangesOption option) {
    return options.contains(option);
  }

  private boolean isWorldReadable(ChangeData cd) throws PermissionBackendException {
    if (!permissionBackend.user(anonymous).change(cd).test(ChangePermission.READ)) {
      return false;
    }
    ProjectState projectState =
        projectCache.get(cd.project()).orElseThrow(illegalState(cd.project()));
    return projectState.statePermitsRead();
  }

  @Nullable
  private Repository openRepoIfNecessary(Project.NameKey project) throws IOException {
    if (has(ALL_COMMITS) || has(CURRENT_COMMIT) || has(COMMIT_FOOTERS)) {
      return repoManager.openRepository(project);
    }
    return null;
  }

  @Nullable
  private RevWalk newRevWalk(@Nullable Repository repo) {
    return repo != null ? new RevWalk(repo) : null;
  }

  private static List<ParentInfo> getParentInfo(List<ParentCommitData> parentsData) {
    List<ParentInfo> result = new ArrayList<>();
    for (ParentCommitData parentData : parentsData) {
      ParentInfo parentInfo = new ParentInfo();
      if (parentData.branchName().isPresent()) {
        parentInfo.branchName = parentData.branchName().get();
      }
      if (parentData.commitId().isPresent()) {
        parentInfo.commitId = parentData.commitId().get().name();
      }
      if (parentData.changeKey().isPresent()) {
        parentInfo.changeId = parentData.changeKey().get().get();
      }
      if (parentData.changeNumber().isPresent()) {
        parentInfo.changeNumber = parentData.changeNumber().get();
      }
      if (parentData.patchSetNumber().isPresent()) {
        parentInfo.patchSetNumber = parentData.patchSetNumber().get();
      }
      if (parentData.changeStatus().isPresent()) {
        parentInfo.changeStatus = parentData.changeStatus().get().name();
      }
      parentInfo.isMergedInTargetBranch = parentData.isMergedInTargetBranch();
      result.add(parentInfo);
    }
    return result;
  }
}
