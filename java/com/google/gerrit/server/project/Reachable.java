// Copyright (C) 2017 The Android Open Source Project
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

package com.google.gerrit.server.project;

import com.google.common.collect.ImmutableList;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.entities.Project;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.logging.Metadata;
import com.google.gerrit.server.logging.TraceContext;
import com.google.gerrit.server.logging.TraceContext.TraceTimer;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.permissions.PermissionBackend.RefFilterOptions;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.ReachabilityChecker;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

/**
 * Report whether a commit is reachable from a set of commits. This is used for checking if a user
 * has read permissions on a commit.
 */
@Singleton
public class Reachable {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final PermissionBackend permissionBackend;

  @Inject
  Reachable(PermissionBackend permissionBackend) {
    this.permissionBackend = permissionBackend;
  }

  /**
   * Returns true if a commit is reachable from a given set of refs. This method enforces
   * permissions on the given set of refs and performs a reachability check. Tags are not filtered
   * separately and will only be returned if reachable by a provided ref.
   */
  public boolean fromRefs(
      Project.NameKey project, Repository repo, RevCommit commit, List<Ref> refs) {
    return fromRefs(project, repo, commit, refs, Optional.empty());
  }

  boolean fromRefs(
      Project.NameKey project,
      Repository repo,
      RevCommit commit,
      List<Ref> refs,
      Optional<CurrentUser> optionalUserProvider) {
    try (RevWalk rw = new RevWalk(repo)) {
      Collection<Ref> filtered =
          optionalUserProvider
              .map(permissionBackend::user)
              .orElseGet(() -> permissionBackend.currentUser())
              .project(project)
              .filter(refs, repo, RefFilterOptions.defaults());
      List<RevCommit> visible = new ArrayList<>();
      for (Ref r : filtered) {
        try {
          visible.add(rw.parseCommit(r.getObjectId()));
        } catch (IncorrectObjectTypeException notCommit) {
          // Its OK for a tag reference to point to a blob or a tree, this
          // is common in the Linux kernel or git.git repository.
          continue;
        } catch (MissingObjectException notHere) {
          // Log the problem with this branch, but keep processing.
          logger.atWarning().log(
              "Reference %s in %s points to dangling object %s",
              r.getName(), repo.getDirectory(), r.getObjectId());
          continue;
        }
      }

      // The filtering above already produces a voluminous trace. To separate the permission check
      // from the reachability check, do the trace here:
      try (TraceTimer timer =
          TraceContext.newTimer(
              "ReachabilityChecker.areAllReachable",
              Metadata.builder().projectName(project.get()).resourceCount(refs.size()).build())) {
        ReachabilityChecker checker = rw.getObjectReader().createReachabilityChecker(rw);
        Optional<RevCommit> unreachable =
            checker.areAllReachable(ImmutableList.of(rw.parseCommit(commit)), visible.stream());
        return !unreachable.isPresent();
      }
    } catch (IOException | PermissionBackendException e) {
      logger.atSevere().withCause(e).log(
          "Cannot verify permissions to commit object %s in repository %s", commit.name(), project);
      return false;
    }
  }
}
