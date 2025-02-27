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

package com.google.gerrit.server.restapi.project;

import com.google.gerrit.exceptions.InvalidMergeStrategyException;
import com.google.gerrit.extensions.client.SubmitType;
import com.google.gerrit.extensions.common.MergeableInfo;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.extensions.restapi.ResourceNotFoundException;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestReadView;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.InMemoryInserter;
import com.google.gerrit.server.git.MergeUtil;
import com.google.gerrit.server.project.BranchResource;
import com.google.inject.Inject;
import java.io.IOException;
import org.eclipse.jgit.errors.NoMergeBaseException;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.merge.Merger;
import org.eclipse.jgit.merge.ResolveMerger;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.kohsuke.args4j.Option;

/** Check the mergeability at current branch for a git object references expression. */
public class CheckMergeability implements RestReadView<BranchResource> {
  private String source;
  private String strategy;
  private SubmitType submitType;
  private final boolean useGitattributesForMerge;

  @Option(
      name = "--source",
      metaVar = "COMMIT",
      usage =
          "the source reference to merge, which could be any git object "
              + "references expression, refer to "
              + "org.eclipse.jgit.lib.Repository#resolve(String)",
      required = true)
  public void setSource(String source) {
    this.source = source;
  }

  @Option(
      name = "--strategy",
      metaVar = "STRATEGY",
      usage = "name of the merge strategy, refer to org.eclipse.jgit.merge.MergeStrategy")
  public void setStrategy(String strategy) {
    this.strategy = strategy;
  }

  private final GitRepositoryManager gitManager;
  private final CommitsCollection commits;

  @Inject
  CheckMergeability(
      GitRepositoryManager gitManager, CommitsCollection commits, @GerritServerConfig Config cfg) {
    this.gitManager = gitManager;
    this.commits = commits;
    this.strategy = MergeUtil.getMergeStrategy(cfg).getName();
    this.submitType = cfg.getEnum("project", null, "submitType", SubmitType.MERGE_IF_NECESSARY);
    this.useGitattributesForMerge = MergeUtil.useGitattributesForMerge(cfg);
  }

  @Override
  public Response<MergeableInfo> apply(BranchResource resource)
      throws IOException,
          BadRequestException,
          ResourceNotFoundException,
          ResourceConflictException {
    if (!(submitType.equals(SubmitType.MERGE_ALWAYS)
        || submitType.equals(SubmitType.MERGE_IF_NECESSARY))) {
      throw new BadRequestException("Submit type: " + submitType + " is not supported");
    }

    MergeableInfo result = new MergeableInfo();
    result.submitType = submitType;
    result.strategy = strategy;
    try (Repository git = gitManager.openRepository(resource.getNameKey());
        RevWalk rw = new RevWalk(git);
        ObjectInserter inserter = new InMemoryInserter(git)) {
      Ref destRef = git.getRefDatabase().exactRef(resource.getRef());
      if (destRef == null) {
        throw new ResourceNotFoundException(resource.getRef());
      }

      RevCommit targetCommit = rw.parseCommit(destRef.getObjectId());

      RevCommit sourceCommit = null;
      try {
        sourceCommit = MergeUtil.resolveCommit(git, rw, source);
        if (!commits.canRead(resource.getProjectState(), git, sourceCommit)) {
          throw new BadRequestException("do not have read permission for: " + source);
        }
      } catch (BadRequestException e) {
        // Throw a unified exception for permission denied and unresolvable commits.
        throw new BadRequestException(
            "Error resolving: '"
                + source
                + "'. Do not have read permission, or failed to resolve to a commit.",
            e);
      }

      if (rw.isMergedInto(sourceCommit, targetCommit)) {
        result.mergeable = true;
        result.commitMerged = true;
        result.contentMerged = true;
        return Response.ok(result);
      }

      Merger m = MergeUtil.newMerger(inserter, git.getConfig(), strategy);
      if (m instanceof ResolveMerger && useGitattributesForMerge) {
        // We need to set the attributes provider before attempting the merge in order to read and
        // honor gitattributes merge settings correctly
        ((ResolveMerger) m).setAttributesNodeProvider(git.createAttributesNodeProvider());
      }
      if (m.merge(false, targetCommit, sourceCommit)) {
        result.mergeable = true;
        result.commitMerged = false;
        result.contentMerged = m.getResultTreeId().equals(targetCommit.getTree());
      } else {
        result.mergeable = false;
        if (m instanceof ResolveMerger) {
          result.conflicts = ((ResolveMerger) m).getUnmergedPaths();
        }
      }
    } catch (InvalidMergeStrategyException e) {
      throw new BadRequestException(e.getMessage());
    } catch (NoMergeBaseException e) {
      // TODO(ekempin) Rather return MergeableInfo with mergeable = false. But then we need a new
      // field in MergeableInfo to carry the message to the client and the frontend needs to be
      // adapted to show the message to the user.
      throw new ResourceConflictException(
          String.format("Change cannot be merged: %s", e.getMessage()), e);
    }
    return Response.ok(result);
  }
}
