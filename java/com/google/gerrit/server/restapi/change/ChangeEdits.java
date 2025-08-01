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

package com.google.gerrit.server.restapi.change;

import static com.google.gerrit.server.project.ProjectCache.illegalState;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.common.RawInputUtil;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.Patch;
import com.google.gerrit.entities.PatchSet;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.api.changes.ChangeEditIdentityType;
import com.google.gerrit.extensions.api.changes.FileContentInput;
import com.google.gerrit.extensions.common.DiffWebLinkInfo;
import com.google.gerrit.extensions.common.EditInfo;
import com.google.gerrit.extensions.common.Input;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.extensions.registration.DynamicMap;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.BinaryResult;
import com.google.gerrit.extensions.restapi.ChildCollection;
import com.google.gerrit.extensions.restapi.DefaultInput;
import com.google.gerrit.extensions.restapi.IdString;
import com.google.gerrit.extensions.restapi.RawInput;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.extensions.restapi.ResourceNotFoundException;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestCollectionCreateView;
import com.google.gerrit.extensions.restapi.RestCollectionDeleteMissingView;
import com.google.gerrit.extensions.restapi.RestCollectionModifyView;
import com.google.gerrit.extensions.restapi.RestModifyView;
import com.google.gerrit.extensions.restapi.RestReadView;
import com.google.gerrit.extensions.restapi.RestView;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.GerritPersonIdent;
import com.google.gerrit.server.WebLinks;
import com.google.gerrit.server.change.ChangeEditResource;
import com.google.gerrit.server.change.ChangeResource;
import com.google.gerrit.server.change.FileContentUtil;
import com.google.gerrit.server.change.FileInfoJson;
import com.google.gerrit.server.change.RevisionResource;
import com.google.gerrit.server.config.UrlFormatter;
import com.google.gerrit.server.edit.ChangeEdit;
import com.google.gerrit.server.edit.ChangeEditJson;
import com.google.gerrit.server.edit.ChangeEditModifier;
import com.google.gerrit.server.edit.ChangeEditUtil;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.validators.CommitValidators;
import com.google.gerrit.server.patch.PatchListNotAvailableException;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.gerrit.server.permissions.RefPermission;
import com.google.gerrit.server.project.InvalidChangeOperationException;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.util.time.TimeUtil;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.util.Base64;
import org.kohsuke.args4j.Option;

@Singleton
public class ChangeEdits implements ChildCollection<ChangeResource, ChangeEditResource> {
  private final DynamicMap<RestView<ChangeEditResource>> views;
  private final Provider<Detail> detail;
  private final ChangeEditUtil editUtil;

  @Inject
  ChangeEdits(
      DynamicMap<RestView<ChangeEditResource>> views,
      Provider<Detail> detail,
      ChangeEditUtil editUtil) {
    this.views = views;
    this.detail = detail;
    this.editUtil = editUtil;
  }

  @Override
  public DynamicMap<RestView<ChangeEditResource>> views() {
    return views;
  }

  @Override
  public RestView<ChangeResource> list() {
    return detail.get();
  }

  @Override
  public ChangeEditResource parse(ChangeResource rsrc, IdString id)
      throws ResourceNotFoundException, AuthException, IOException {
    Optional<ChangeEdit> edit = editUtil.byChange(rsrc.getNotes(), rsrc.getUser());
    if (!edit.isPresent()) {
      throw new ResourceNotFoundException(id);
    }
    return new ChangeEditResource(rsrc, edit.get(), id.get());
  }

  /**
   * Create handler that is activated when collection element is accessed but doesn't exist, e. g.
   * PUT request with a path was called but change edit wasn't created yet. Change edit is created
   * and PUT handler is called.
   */
  @Singleton
  public static class Create
      implements RestCollectionCreateView<ChangeResource, ChangeEditResource, FileContentInput> {
    private final Put putEdit;

    @Inject
    Create(Put putEdit) {
      this.putEdit = putEdit;
    }

    @Override
    public Response<Object> apply(
        ChangeResource resource, IdString id, FileContentInput fileContentInput)
        throws AuthException,
            BadRequestException,
            ResourceConflictException,
            IOException,
            PermissionBackendException {
      return putEdit.apply(resource, id.get(), fileContentInput);
    }
  }

  @Singleton
  public static class DeleteFile
      implements RestCollectionDeleteMissingView<ChangeResource, ChangeEditResource, Input> {
    private final DeleteContent deleteContent;

    @Inject
    DeleteFile(DeleteContent deleteContent) {
      this.deleteContent = deleteContent;
    }

    @Override
    public Response<Object> apply(ChangeResource rsrc, IdString id, Input input)
        throws IOException,
            AuthException,
            BadRequestException,
            ResourceConflictException,
            PermissionBackendException {
      return deleteContent.apply(rsrc, id.get());
    }
  }

  // TODO(davido): Turn the boolean options to ChangeEditOption enum,
  // like it's already the case for ListChangesOption/ListGroupsOption
  public static class Detail implements RestReadView<ChangeResource> {
    private final ChangeEditUtil editUtil;
    private final ChangeEditJson editJson;
    private final FileInfoJson fileInfoJson;
    private final Revisions revisions;

    private String base;
    private boolean list;
    private boolean downloadCommands;

    @Option(name = "--base", metaVar = "revision-id")
    public void setBase(String base) {
      this.base = base;
    }

    @Option(name = "--list")
    public void setList(boolean list) {
      this.list = list;
    }

    @Option(name = "--download-commands")
    public void setDownloadCommands(boolean downloadCommands) {
      this.downloadCommands = downloadCommands;
    }

    @Inject
    Detail(
        ChangeEditUtil editUtil,
        ChangeEditJson editJson,
        FileInfoJson fileInfoJson,
        Revisions revisions) {
      this.editJson = editJson;
      this.editUtil = editUtil;
      this.fileInfoJson = fileInfoJson;
      this.revisions = revisions;
    }

    @Override
    public Response<EditInfo> apply(ChangeResource rsrc)
        throws AuthException,
            IOException,
            ResourceNotFoundException,
            ResourceConflictException,
            PermissionBackendException {
      Optional<ChangeEdit> edit = editUtil.byChange(rsrc.getNotes(), rsrc.getUser());
      if (!edit.isPresent()) {
        return Response.none();
      }

      EditInfo editInfo = editJson.toEditInfo(edit.get(), downloadCommands);
      if (list) {
        PatchSet basePatchSet = null;
        if (base != null) {
          RevisionResource baseResource = revisions.parse(rsrc, IdString.fromDecoded(base));
          basePatchSet = baseResource.getPatchSet();
        }
        try {
          editInfo.files =
              fileInfoJson.getFileInfoMap(
                  rsrc.getChange(), edit.get().getEditCommit(), basePatchSet);
        } catch (PatchListNotAvailableException e) {
          throw new ResourceNotFoundException(e.getMessage());
        }
      }
      return Response.ok(editInfo);
    }
  }

  /**
   * Post to edit collection resource. Two different operations are supported:
   *
   * <ul>
   *   <li>Create non existing change edit
   *   <li>Restore path in existing change edit
   * </ul>
   *
   * The combination of two operations in one request is supported.
   */
  @Singleton
  public static class Post
      implements RestCollectionModifyView<ChangeResource, ChangeEditResource, Post.Input> {
    public static class Input {
      public String restorePath;
      public String oldPath;
      public String newPath;
    }

    private final ChangeEditModifier editModifier;
    private final GitRepositoryManager repositoryManager;

    @Inject
    Post(ChangeEditModifier editModifier, GitRepositoryManager repositoryManager) {
      this.editModifier = editModifier;
      this.repositoryManager = repositoryManager;
    }

    @Override
    public Response<Object> apply(ChangeResource resource, Post.Input postInput)
        throws AuthException,
            BadRequestException,
            IOException,
            ResourceConflictException,
            PermissionBackendException {
      Project.NameKey project = resource.getProject();
      try (Repository repository = repositoryManager.openRepository(project)) {
        if (isRestoreFile(postInput)) {
          editModifier.restoreFile(repository, resource.getNotes(), postInput.restorePath);
        } else if (isRenameFile(postInput)) {
          editModifier.renameFile(
              repository, resource.getNotes(), postInput.oldPath, postInput.newPath);
        } else {
          editModifier.createEdit(repository, resource.getNotes());
        }
      } catch (InvalidChangeOperationException e) {
        throw new ResourceConflictException(e.getMessage());
      }
      return Response.none();
    }

    private static boolean isRestoreFile(Post.Input postInput) {
      return postInput != null && !Strings.isNullOrEmpty(postInput.restorePath);
    }

    private static boolean isRenameFile(Post.Input postInput) {
      return postInput != null
          && !Strings.isNullOrEmpty(postInput.oldPath)
          && !Strings.isNullOrEmpty(postInput.newPath);
    }
  }

  /** Put handler that is activated when PUT request is called on collection element. */
  @Singleton
  public static class Put implements RestModifyView<ChangeEditResource, FileContentInput> {

    private static final Pattern BINARY_DATA_PATTERN =
        Pattern.compile("data:([\\w/.-]*);([\\w]+),(.*)");
    private static final String BASE64 = "base64";

    private final ChangeEditModifier editModifier;
    private final GitRepositoryManager repositoryManager;
    private final EditMessage editMessage;

    @Inject
    Put(
        ChangeEditModifier editModifier,
        GitRepositoryManager repositoryManager,
        EditMessage editMessage) {
      this.editModifier = editModifier;
      this.repositoryManager = repositoryManager;
      this.editMessage = editMessage;
    }

    @Override
    public Response<Object> apply(ChangeEditResource rsrc, FileContentInput fileContentInput)
        throws AuthException,
            BadRequestException,
            ResourceConflictException,
            IOException,
            PermissionBackendException {
      return apply(rsrc.getChangeResource(), rsrc.getPath(), fileContentInput);
    }

    @Nullable
    private Integer octalAsDecimal(Integer inputMode) {
      if (inputMode == null) {
        return null;
      }

      return Integer.parseInt(Integer.toString(inputMode), 8);
    }

    public Response<Object> apply(
        ChangeResource rsrc, String path, FileContentInput fileContentInput)
        throws AuthException,
            BadRequestException,
            ResourceConflictException,
            IOException,
            PermissionBackendException {

      if (fileContentInput.content == null && fileContentInput.binaryContent == null) {
        throw new BadRequestException("either content or binary_content is required");
      }

      RawInput newContent;
      if (fileContentInput.binaryContent != null) {
        Matcher m = BINARY_DATA_PATTERN.matcher(fileContentInput.binaryContent);
        if (m.matches() && BASE64.equals(m.group(2))) {
          newContent = RawInputUtil.create(Base64.decode(m.group(3)));
        } else {
          throw new BadRequestException("binary_content must be encoded as base64 data uri");
        }
      } else {
        newContent = fileContentInput.content;
      }

      if (Patch.COMMIT_MSG.equals(path) && fileContentInput.binaryContent == null) {
        EditMessage.Input editMessageInput = new EditMessage.Input();
        editMessageInput.message =
            new String(ByteStreams.toByteArray(newContent.getInputStream()), UTF_8);
        return editMessage.apply(rsrc, editMessageInput);
      }

      if (Strings.isNullOrEmpty(path) || path.charAt(0) == '/') {
        throw new ResourceConflictException("Invalid path: " + path);
      }

      try (Repository repository = repositoryManager.openRepository(rsrc.getProject())) {
        editModifier.modifyFile(
            repository,
            rsrc.getNotes(),
            path,
            newContent,
            octalAsDecimal(fileContentInput.fileMode));
      } catch (InvalidChangeOperationException e) {
        throw new ResourceConflictException(e.getMessage());
      }
      return Response.none();
    }
  }

  /**
   * Handler to delete a file.
   *
   * <p>This deletes the file from the repository completely. This is not the same as reverting or
   * restoring a file to its previous contents.
   */
  @Singleton
  public static class DeleteContent implements RestModifyView<ChangeEditResource, Input> {

    private final ChangeEditModifier editModifier;
    private final GitRepositoryManager repositoryManager;

    @Inject
    DeleteContent(ChangeEditModifier editModifier, GitRepositoryManager repositoryManager) {
      this.editModifier = editModifier;
      this.repositoryManager = repositoryManager;
    }

    @Override
    public Response<Object> apply(ChangeEditResource rsrc, Input input)
        throws AuthException,
            BadRequestException,
            ResourceConflictException,
            IOException,
            PermissionBackendException {
      return apply(rsrc.getChangeResource(), rsrc.getPath());
    }

    public Response<Object> apply(ChangeResource rsrc, String filePath)
        throws AuthException,
            BadRequestException,
            IOException,
            ResourceConflictException,
            PermissionBackendException {
      try (Repository repository = repositoryManager.openRepository(rsrc.getProject())) {
        editModifier.deleteFile(repository, rsrc.getNotes(), filePath);
      } catch (InvalidChangeOperationException e) {
        throw new ResourceConflictException(e.getMessage());
      }
      return Response.none();
    }
  }

  public static class Get implements RestReadView<ChangeEditResource> {
    private final FileContentUtil fileContentUtil;
    private final ProjectCache projectCache;
    private final GetMessage getMessage;

    @Option(
        name = "--base",
        aliases = {"-b"},
        usage = "whether to load the content on the base revision instead of the change edit")
    private boolean base;

    @Inject
    Get(FileContentUtil fileContentUtil, ProjectCache projectCache, GetMessage getMessage) {
      this.fileContentUtil = fileContentUtil;
      this.projectCache = projectCache;
      this.getMessage = getMessage;
    }

    @Override
    public Response<BinaryResult> apply(ChangeEditResource rsrc) throws AuthException, IOException {
      try {
        if (Patch.COMMIT_MSG.equals(rsrc.getPath())) {
          return getMessage.apply(rsrc.getChangeResource());
        }

        ChangeEdit edit = rsrc.getChangeEdit();
        Project.NameKey project = rsrc.getChangeResource().getProject();
        return Response.ok(
            fileContentUtil.getContent(
                projectCache.get(project).orElseThrow(illegalState(project)),
                base ? edit.getBasePatchSet().commitId() : edit.getEditCommit(),
                rsrc.getPath(),
                null));
      } catch (ResourceNotFoundException | BadRequestException e) {
        return Response.none();
      }
    }
  }

  @Singleton
  public static class GetMeta implements RestReadView<ChangeEditResource> {
    private final WebLinks webLinks;

    @Inject
    GetMeta(WebLinks webLinks) {
      this.webLinks = webLinks;
    }

    @Override
    public Response<FileInfo> apply(ChangeEditResource rsrc) {
      FileInfo r = new FileInfo();
      ChangeEdit edit = rsrc.getChangeEdit();
      Change change = edit.getChange();
      ImmutableList<DiffWebLinkInfo> links =
          webLinks.getDiffLinks(
              change.getProject().get(),
              change.getChangeId(),
              edit.getBasePatchSet().number(),
              edit.getBasePatchSet().refName(),
              rsrc.getPath(),
              0,
              edit.getRefName(),
              rsrc.getPath());
      r.webLinks = links.isEmpty() ? null : links;
      return Response.ok(r);
    }

    public static class FileInfo {
      public List<DiffWebLinkInfo> webLinks;
    }
  }

  @Singleton
  public static class EditMessage implements RestModifyView<ChangeResource, EditMessage.Input> {
    public static class Input {
      @DefaultInput public String message;
    }

    private final ChangeEditModifier editModifier;
    private final GitRepositoryManager repositoryManager;

    @Inject
    EditMessage(ChangeEditModifier editModifier, GitRepositoryManager repositoryManager) {
      this.editModifier = editModifier;
      this.repositoryManager = repositoryManager;
    }

    @Override
    public Response<Object> apply(ChangeResource rsrc, EditMessage.Input editMessageInput)
        throws AuthException,
            IOException,
            BadRequestException,
            ResourceConflictException,
            PermissionBackendException {
      if (editMessageInput == null || Strings.isNullOrEmpty(editMessageInput.message)) {
        throw new BadRequestException("commit message must be provided");
      }

      Project.NameKey project = rsrc.getProject();
      try (Repository repository = repositoryManager.openRepository(project)) {
        editModifier.modifyMessage(repository, rsrc.getNotes(), editMessageInput.message);
      } catch (InvalidChangeOperationException e) {
        throw new ResourceConflictException(e.getMessage());
      }

      return Response.none();
    }
  }

  public static class GetMessage implements RestReadView<ChangeResource> {
    private final GitRepositoryManager repoManager;
    private final ChangeEditUtil editUtil;

    @Option(
        name = "--base",
        aliases = {"-b"},
        usage = "whether to load the message on the base revision instead of the change edit")
    private boolean base;

    @Inject
    GetMessage(GitRepositoryManager repoManager, ChangeEditUtil editUtil) {
      this.repoManager = repoManager;
      this.editUtil = editUtil;
    }

    @Override
    public Response<BinaryResult> apply(ChangeResource rsrc)
        throws AuthException, IOException, ResourceNotFoundException {
      Optional<ChangeEdit> edit = editUtil.byChange(rsrc.getNotes(), rsrc.getUser());
      String msg;
      if (edit.isPresent()) {
        if (base) {
          try (Repository repo = repoManager.openRepository(rsrc.getProject());
              RevWalk rw = new RevWalk(repo)) {
            RevCommit commit = rw.parseCommit(edit.get().getBasePatchSet().commitId());
            msg = commit.getFullMessage();
          }
        } else {
          msg = edit.get().getEditCommit().getFullMessage();
        }

        return Response.ok(
            BinaryResult.create(msg)
                .setContentType(FileContentUtil.TEXT_X_GERRIT_COMMIT_MESSAGE)
                .base64());
      }
      throw new ResourceNotFoundException();
    }
  }

  @Singleton
  public static class EditIdentity implements RestModifyView<ChangeResource, EditIdentity.Input> {
    public static class Input {
      public String name;
      public String email;
      public ChangeEditIdentityType type;
    }

    private final ChangeEditModifier editModifier;
    private final GitRepositoryManager repositoryManager;
    private final PermissionBackend permissionBackend;
    private final Provider<CurrentUser> self;
    private final Provider<PersonIdent> serverIdent;
    private final DynamicItem<UrlFormatter> urlFormatter;

    @Inject
    EditIdentity(
        ChangeEditModifier editModifier,
        GitRepositoryManager repositoryManager,
        PermissionBackend permissionBackend,
        Provider<CurrentUser> self,
        @GerritPersonIdent Provider<PersonIdent> serverIdent,
        DynamicItem<UrlFormatter> urlFormatter) {
      this.editModifier = editModifier;
      this.repositoryManager = repositoryManager;
      this.permissionBackend = permissionBackend;
      this.self = self;
      this.serverIdent = serverIdent;
      this.urlFormatter = urlFormatter;
    }

    @Override
    public Response<Object> apply(ChangeResource rsrc, EditIdentity.Input input)
        throws AuthException,
            IOException,
            BadRequestException,
            ResourceConflictException,
            PermissionBackendException {
      if (input == null || input.type == null) {
        throw new BadRequestException("type must be provided");
      }
      if (input.name == null && input.email == null) {
        throw new BadRequestException("name or email must be provided");
      }
      input.name = Strings.nullToEmpty(input.name);
      input.email = Strings.nullToEmpty(input.email);

      RefPermission perm;
      switch (input.type) {
        case AUTHOR:
          perm = RefPermission.FORGE_AUTHOR;
          break;
        case COMMITTER:
        default:
          perm = RefPermission.FORGE_COMMITTER;
          break;
      }

      PersonIdent newIdent =
          new PersonIdent(input.name, input.email, TimeUtil.now(), serverIdent.get().getZoneId());

      if (!input.email.isEmpty() && !self.get().asIdentifiedUser().hasEmailAddress(input.email)) {
        try {
          permissionBackend.user(self.get()).ref(rsrc.getNotes().getChange().getDest()).check(perm);
        } catch (AuthException e) {
          throw new ResourceConflictException(
              CommitValidators.invalidEmail(
                      input.type.toString(),
                      newIdent,
                      self.get().asIdentifiedUser(),
                      urlFormatter.get())
                  .getMessage(),
              e);
        }
      }

      try (Repository repository = repositoryManager.openRepository(rsrc.getProject())) {
        editModifier.modifyIdentity(repository, rsrc.getNotes(), newIdent, input.type);
      } catch (InvalidChangeOperationException e) {
        throw new ResourceConflictException(e.getMessage());
      }

      return Response.none();
    }
  }
}
