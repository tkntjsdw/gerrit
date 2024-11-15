// Copyright (C) 2023 The Android Open Source Project
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

import static com.google.gerrit.server.project.ProjectCache.illegalState;

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.extensions.client.ReviewerState;
import com.google.gerrit.extensions.common.AccountVisibility;
import com.google.gerrit.extensions.common.SuggestedReviewerInfo;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestReadView;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.gerrit.server.permissions.RefPermission;
import com.google.gerrit.server.project.BranchResource;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.restapi.change.ReviewersUtil;
import com.google.gerrit.server.restapi.change.ReviewersUtil.VisibilityControl;
import com.google.gerrit.server.restapi.change.SuggestReviewers;
import com.google.inject.Inject;
import com.google.inject.Provider;
import java.io.IOException;
import java.util.List;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Config;
import org.kohsuke.args4j.Option;

public class SuggestBranchReviewers extends SuggestReviewers
    implements RestReadView<BranchResource> {

  private final PermissionBackend permissionBackend;
  private final Provider<CurrentUser> self;
  private final ProjectCache projectCache;

  private boolean excludeGroups;
  private ReviewerState reviewerState = ReviewerState.REVIEWER;

  @Option(
      name = "--exclude-groups",
      aliases = {"-e"},
      usage = "exclude groups from query")
  @CanIgnoreReturnValue
  public SuggestBranchReviewers setExcludeGroups(boolean excludeGroups) {
    this.excludeGroups = excludeGroups;
    return this;
  }

  @Option(
      name = "--reviewer-state",
      usage =
          "The type of reviewers that should be suggested"
              + " (can be 'REVIEWER' or 'CC', default is 'REVIEWER')")
  @CanIgnoreReturnValue
  public SuggestBranchReviewers setReviewerState(ReviewerState reviewerState) {
    this.reviewerState = reviewerState;
    return this;
  }

  @Inject
  SuggestBranchReviewers(
      AccountVisibility av,
      PermissionBackend permissionBackend,
      Provider<CurrentUser> self,
      @GerritServerConfig Config cfg,
      ReviewersUtil reviewersUtil,
      ProjectCache projectCache) {
    super(av, cfg, reviewersUtil);
    this.permissionBackend = permissionBackend;
    this.self = self;
    this.projectCache = projectCache;
  }

  @Override
  public Response<List<SuggestedReviewerInfo>> apply(BranchResource rsrc)
      throws AuthException,
          BadRequestException,
          IOException,
          ConfigInvalidException,
          PermissionBackendException {
    if (!self.get().isIdentifiedUser()) {
      throw new AuthException("Authentication required");
    }
    if (reviewerState.equals(ReviewerState.REMOVED)) {
      throw new BadRequestException(
          String.format("Unsupported reviewer state: %s", ReviewerState.REMOVED));
    }

    return Response.ok(
        reviewersUtil.suggestReviewers(
            reviewerState,
            null,
            this,
            projectCache
                .get(rsrc.getProjectState().getNameKey())
                .orElseThrow(illegalState(rsrc.getProjectState().getNameKey())),
            getVisibility(rsrc.getBranchKey()),
            excludeGroups));
  }

  private VisibilityControl getVisibility(BranchNameKey branch) {
    return account -> {
      return permissionBackend.absentUser(account).ref(branch).testOrFalse(RefPermission.READ);
    };
  }
}
