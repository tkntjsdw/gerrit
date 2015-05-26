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

package com.google.gerrit.server.project;

import com.google.common.base.Predicate;
import com.google.common.base.Strings;
import com.google.common.collect.ComparisonChain;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.Sets;
import com.google.gerrit.extensions.api.projects.BranchInfo;
import com.google.gerrit.extensions.common.ActionInfo;
import com.google.gerrit.extensions.common.WebLinkInfo;
import com.google.gerrit.extensions.registration.DynamicMap;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.ResourceNotFoundException;
import com.google.gerrit.extensions.restapi.RestReadView;
import com.google.gerrit.extensions.restapi.RestView;
import com.google.gerrit.extensions.webui.UiAction;
import com.google.gerrit.reviewdb.client.RefNames;
import com.google.gerrit.server.WebLinks;
import com.google.gerrit.server.extensions.webui.UiActions;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.inject.Inject;
import com.google.inject.util.Providers;

import dk.brics.automaton.RegExp;
import dk.brics.automaton.RunAutomaton;

import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.kohsuke.args4j.Option;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;

public class ListBranches implements RestReadView<ProjectResource> {
  private final GitRepositoryManager repoManager;
  private final DynamicMap<RestView<BranchResource>> branchViews;
  private final WebLinks webLinks;

  @Option(name = "--limit", aliases = {"-n"}, metaVar = "CNT", usage = "maximum number of branches to list")
  public void setLimit(int limit) {
    this.limit = limit;
  }

  @Option(name = "--start", aliases = {"-s"}, metaVar = "CNT", usage = "number of branches to skip")
  public void setStart(int start) {
    this.start = start;
  }

  @Option(name = "--match", aliases = {"-m"}, metaVar = "MATCH", usage = "match branches substring")
  public void setMatchSubstring(String matchSubstring) {
    this.matchSubstring = matchSubstring;
  }

  @Option(name = "--regex", aliases = {"-r"}, metaVar = "REGEX", usage = "match branches regex")
  public void setMatchRegex(String matchRegex) {
    this.matchRegex = matchRegex;
  }

  private int limit;
  private int start;
  private String matchSubstring;
  private String matchRegex;

  @Inject
  public ListBranches(GitRepositoryManager repoManager,
      DynamicMap<RestView<BranchResource>> branchViews,
      WebLinks webLinks) {
    this.repoManager = repoManager;
    this.branchViews = branchViews;
    this.webLinks = webLinks;
  }

  @Override
  public List<BranchInfo> apply(ProjectResource rsrc)
      throws ResourceNotFoundException, IOException, BadRequestException {
    FluentIterable<BranchInfo> branches = allBranches(rsrc);
    branches = filterBranches(branches);
    if (start > 0) {
      branches = branches.skip(start);
    }
    if (limit > 0) {
      branches = branches.limit(limit);
    }
    return branches.toList();
  }

  private FluentIterable<BranchInfo> allBranches(ProjectResource rsrc)
      throws IOException, ResourceNotFoundException {
    List<Ref> refs;
    try (Repository db = repoManager.openRepository(rsrc.getNameKey())) {
      Collection<Ref> heads =
          db.getRefDatabase().getRefs(Constants.R_HEADS).values();
      refs = new ArrayList<>(heads.size() + 3);
      refs.addAll(heads);
      addRef(db, refs, Constants.HEAD);
      addRef(db, refs, RefNames.REFS_CONFIG);
      addRef(db, refs, RefNames.REFS_USERS_DEFAULT);
    } catch (RepositoryNotFoundException noGitRepository) {
      throw new ResourceNotFoundException();
    }

    Set<String> targets = Sets.newHashSetWithExpectedSize(1);
    for (Ref ref : refs) {
      if (ref.isSymbolic()) {
        targets.add(ref.getTarget().getName());
      }
    }

    List<BranchInfo> branches = new ArrayList<>(refs.size());
    for (Ref ref : refs) {
      if (ref.isSymbolic()) {
        // A symbolic reference to another branch, instead of
        // showing the resolved value, show the name it references.
        //
        String target = ref.getTarget().getName();
        RefControl targetRefControl = rsrc.getControl().controlForRef(target);
        if (!targetRefControl.isVisible()) {
          continue;
        }
        if (target.startsWith(Constants.R_HEADS)) {
          target = target.substring(Constants.R_HEADS.length());
        }

        BranchInfo b = new BranchInfo();
        b.ref = ref.getName();
        b.revision = target;
        branches.add(b);

        if (!Constants.HEAD.equals(ref.getName())) {
          b.canDelete = targetRefControl.canDelete() ? true : null;
        }
        continue;
      }

      RefControl refControl = rsrc.getControl().controlForRef(ref.getName());
      if (refControl.isVisible()) {
        branches.add(createBranchInfo(ref, refControl, targets));
      }
    }
    Collections.sort(branches, new BranchComparator());
    return FluentIterable.from(branches);
  }

  private static class BranchComparator implements Comparator<BranchInfo> {
    @Override
    public int compare(BranchInfo a, BranchInfo b) {
      return ComparisonChain.start()
          .compareTrueFirst(isHead(a), isHead(b))
          .compareTrueFirst(isConfig(a), isConfig(b))
          .compare(a.ref, b.ref)
          .result();
    }

    private static boolean isHead(BranchInfo i) {
      return Constants.HEAD.equals(i.ref);
    }

    private static boolean isConfig(BranchInfo i) {
      return RefNames.REFS_CONFIG.equals(i.ref);
    }
  }

  private static void addRef(Repository db, List<Ref> refs, String name)
      throws IOException {
    Ref ref = db.getRef(name);
    if (ref != null) {
      refs.add(ref);
    }
  }

  private FluentIterable<BranchInfo> filterBranches(
      FluentIterable<BranchInfo> branches) throws BadRequestException {
    if (!Strings.isNullOrEmpty(matchSubstring)) {
      branches = branches.filter(new SubstringPredicate(matchSubstring));
    } else if (!Strings.isNullOrEmpty(matchRegex)) {
      branches = branches.filter(new RegexPredicate(matchRegex));
    }
    return branches;
  }

  private static class SubstringPredicate implements Predicate<BranchInfo> {
    private final String substring;

    private SubstringPredicate(String substring) {
      this.substring = substring.toLowerCase(Locale.US);
    }

    @Override
    public boolean apply(BranchInfo in) {
      String ref = in.ref;
      if (ref.startsWith(Constants.R_HEADS)) {
        ref = ref.substring(Constants.R_HEADS.length());
      }
      ref = ref.toLowerCase(Locale.US);
      return ref.contains(substring);
    }
  }

  private static class RegexPredicate implements Predicate<BranchInfo> {
    private final RunAutomaton a;

    private RegexPredicate(String regex) throws BadRequestException {
      if (regex.startsWith("^")) {
        regex = regex.substring(1);
        if (regex.endsWith("$") && !regex.endsWith("\\$")) {
          regex = regex.substring(0, regex.length() - 1);
        }
      }
      try {
        a = new RunAutomaton(new RegExp(regex).toAutomaton());
      } catch (IllegalArgumentException e) {
        throw new BadRequestException(e.getMessage());
      }
    }

    @Override
    public boolean apply(BranchInfo in) {
      if (!in.ref.startsWith(Constants.R_HEADS)){
        return a.run(in.ref);
      } else {
        return a.run(in.ref.substring(Constants.R_HEADS.length()));
      }
    }
  }

  private BranchInfo createBranchInfo(Ref ref, RefControl refControl,
      Set<String> targets) {
    BranchInfo info = new BranchInfo();
    info.ref = ref.getName();
    info.revision = ref.getObjectId() != null ? ref.getObjectId().name() : null;
    info.canDelete = !targets.contains(ref.getName()) && refControl.canDelete()
        ? true : null;
    for (UiAction.Description d : UiActions.from(
        branchViews,
        new BranchResource(refControl.getProjectControl(), info),
        Providers.of(refControl.getCurrentUser()))) {
      if (info.actions == null) {
        info.actions = new TreeMap<>();
      }
      info.actions.put(d.getId(), new ActionInfo(d));
    }
    FluentIterable<WebLinkInfo> links =
        webLinks.getBranchLinks(
            refControl.getProjectControl().getProject().getName(), ref.getName());
    info.webLinks = links.isEmpty() ? null : links.toList();
    return info;
  }
}
