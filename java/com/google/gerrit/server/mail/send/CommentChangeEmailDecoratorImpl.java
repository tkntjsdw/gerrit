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

package com.google.gerrit.server.mail.send;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.gerrit.entities.Patch.PATCHSET_LEVEL;
import static java.util.stream.Collectors.toList;

import com.google.auto.factory.AutoFactory;
import com.google.auto.factory.Provided;
import com.google.common.base.Strings;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.common.data.FilenameComparator;
import com.google.gerrit.entities.Account;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.Comment;
import com.google.gerrit.entities.HumanComment;
import com.google.gerrit.entities.NotifyConfig.NotifyType;
import com.google.gerrit.entities.Patch;
import com.google.gerrit.entities.Project;
import com.google.gerrit.entities.SubmitRequirement;
import com.google.gerrit.entities.SubmitRequirementResult;
import com.google.gerrit.exceptions.NoSuchEntityException;
import com.google.gerrit.exceptions.StorageException;
import com.google.gerrit.extensions.api.changes.NotifyHandling;
import com.google.gerrit.extensions.api.changes.RecipientType;
import com.google.gerrit.mail.MailHeader;
import com.google.gerrit.mail.MailProcessingUtil;
import com.google.gerrit.server.CommentsUtil;
import com.google.gerrit.server.config.GerritServerConfig;
import com.google.gerrit.server.mail.receive.Protocol;
import com.google.gerrit.server.patch.PatchFile;
import com.google.gerrit.server.patch.filediff.FileDiffOutput;
import com.google.gerrit.server.util.LabelVote;
import java.io.IOException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.james.mime4j.dom.field.FieldName;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;

/** Send comments, after the author of them hit used Publish Comments in the UI. */
@AutoFactory
public class CommentChangeEmailDecoratorImpl implements CommentChangeEmailDecorator {
  protected static final FluentLogger logger = FluentLogger.forEnclosingClass();

  protected class FileCommentGroup {

    public String filename;
    public int patchSetId;
    public PatchFile fileData;
    public List<Comment> comments = new ArrayList<>();

    /** Returns a web link to a comment for a change. */
    @Nullable
    public String getCommentLink(String uuid) {
      return args.urlFormatter
          .get()
          .getInlineCommentView(changeEmail.getChange(), uuid)
          .map(EmailArguments::addUspParam)
          .orElse(null);
    }

    /** Returns a web link to the comment tab view of a change. */
    @Nullable
    public String getCommentsTabLink() {
      return args.urlFormatter
          .get()
          .getCommentsTabView(changeEmail.getChange())
          .map(EmailArguments::addUspParam)
          .orElse(null);
    }

    /**
     * Returns a title for the group, i.e. "Commit Message", "Merge List", or "File [[filename]]".
     */
    public String getTitle() {
      if (Patch.COMMIT_MSG.equals(filename)) {
        return "Commit Message";
      } else if (Patch.MERGE_LIST.equals(filename)) {
        return "Merge List";
      } else if (Patch.PATCHSET_LEVEL.equals(filename)) {
        return "Patchset";
      } else {
        return "File " + filename;
      }
    }
  }

  protected EmailArguments args;
  protected OutgoingEmail email;
  protected ChangeEmail changeEmail;
  protected List<? extends Comment> inlineComments = Collections.emptyList();
  @Nullable protected String patchSetComment;
  protected List<LabelVote> labels = ImmutableList.of();
  protected final CommentsUtil commentsUtil;
  private final boolean incomingEmailEnabled;
  private final String replyToAddress;
  private final Supplier<Map<SubmitRequirement, SubmitRequirementResult>>
      preUpdateSubmitRequirementResultsSupplier;
  private final Map<SubmitRequirement, SubmitRequirementResult> postUpdateSubmitRequirementResults;

  public CommentChangeEmailDecoratorImpl(
      @Provided EmailArguments args,
      @Provided CommentsUtil commentsUtil,
      @Provided @GerritServerConfig Config cfg,
      Project.NameKey project,
      Change.Id changeId,
      ObjectId preUpdateMetaId,
      Map<SubmitRequirement, SubmitRequirementResult> postUpdateSubmitRequirementResults) {
    this.args = args;
    this.commentsUtil = commentsUtil;
    this.incomingEmailEnabled =
        cfg.getEnum("receiveemail", null, "protocol", Protocol.NONE).ordinal()
            > Protocol.NONE.ordinal();
    this.replyToAddress = cfg.getString("sendemail", null, "replyToAddress");
    this.preUpdateSubmitRequirementResultsSupplier =
        Suppliers.memoize(
            () ->
                // Triggers an (expensive) evaluation of the submit requirements. This is OK since
                // all callers sent this email asynchronously, see EmailReviewComments.
                args.newChangeData(project, changeId, preUpdateMetaId)
                    .submitRequirementsIncludingLegacy());
    this.postUpdateSubmitRequirementResults = postUpdateSubmitRequirementResults;
  }

  @Override
  public void setComments(List<? extends Comment> comments) {
    inlineComments = comments;
  }

  @Override
  public void setPatchSetComment(@Nullable String comment) {
    this.patchSetComment = comment;
  }

  @Override
  public void setLabels(List<LabelVote> labels) {
    this.labels = labels;
  }

  @Override
  public void init(OutgoingEmail email, ChangeEmail changeEmail) {
    this.email = email;
    this.changeEmail = changeEmail;
    // Add header that enables identifying comments on parsed email.
    // Grouping is currently done by timestamp.
    email.setHeader(MailHeader.COMMENT_DATE.fieldName(), changeEmail.getTimestamp());

    if (incomingEmailEnabled) {
      if (replyToAddress == null) {
        // Remove Reply-To and use outbound SMTP (default) instead.
        email.removeHeader(FieldName.REPLY_TO);
      } else {
        email.setHeader(FieldName.REPLY_TO, replyToAddress);
      }
    }
    changeEmail.markAsReply();
  }

  /**
   * Returns a list of FileCommentGroup objects representing the inline comments grouped by the
   * file.
   */
  private List<CommentChangeEmailDecoratorImpl.FileCommentGroup> getGroupedInlineComments(
      Repository repo) {
    List<CommentChangeEmailDecoratorImpl.FileCommentGroup> groups = new ArrayList<>();

    // Loop over the comments and collect them into groups based on the file
    // location of the comment.
    FileCommentGroup currentGroup = null;
    for (Comment c : inlineComments) {
      // If it's a new group:
      if (currentGroup == null
          || !c.key.filename.equals(currentGroup.filename)
          || c.key.patchSetId != currentGroup.patchSetId) {
        currentGroup = new FileCommentGroup();
        currentGroup.filename = c.key.filename;
        currentGroup.patchSetId = c.key.patchSetId;
        // Get the modified files:
        Map<String, FileDiffOutput> modifiedFiles = changeEmail.listModifiedFiles(c.key.patchSetId);

        groups.add(currentGroup);
        if (modifiedFiles != null && !modifiedFiles.isEmpty()) {
          try {
            currentGroup.fileData =
                loadPatchFile(
                    repo, modifiedFiles, c.key.filename, changeEmail.getPatchSet().commitId());
          } catch (IOException e) {
            logger.atWarning().withCause(e).log(
                "Cannot load %s from %s in %s",
                c.key.filename,
                modifiedFiles.values().iterator().next().newCommitId().name(),
                changeEmail.getProjectState().getName());
            currentGroup.fileData = null;
          }
        }
      }

      if (currentGroup.filename.equals(PATCHSET_LEVEL) || currentGroup.fileData != null) {
        currentGroup.comments.add(c);
      }
    }

    groups.sort(Comparator.comparing(g -> g.filename, FilenameComparator.INSTANCE));
    return groups;
  }

  private PatchFile loadPatchFile(
      Repository repo,
      Map<String, FileDiffOutput> modifiedFiles,
      String fileName,
      ObjectId commitId)
      throws IOException {
    try {
      return new PatchFile(repo, modifiedFiles, fileName);
    } catch (MissingObjectException e) {
      // check if the file has not been modified then is an unchanged file
      if (!isModifiedFile(modifiedFiles, fileName)) {
        return new PatchFile(repo, fileName, commitId);
      }
      throw e;
    }
  }

  private boolean isModifiedFile(Map<String, FileDiffOutput> modifiedFiles, String fileName) {
    return modifiedFiles.values().stream()
        .anyMatch(f -> f.newPath().map(v -> v.equals(fileName)).orElse(false));
  }

  /** Get the set of accounts whose comments have been replied to in this email. */
  protected HashSet<Account.Id> getReplyAccounts() {
    HashSet<Account.Id> replyAccounts = new HashSet<>();
    // Track visited parent UUIDs to avoid cycles.
    HashSet<String> visitedUuids = new HashSet<>();

    for (Comment comment : inlineComments) {
      visitedUuids.add(comment.key.uuid);
      // Traverse the parent relation to the top of the comment thread.
      Comment current = comment;
      while (current.parentUuid != null && !visitedUuids.contains(current.parentUuid)) {
        Optional<HumanComment> optParent = getParent(current);
        if (!optParent.isPresent()) {
          // There is a parent UUID, but it cannot be loaded, break from the comment thread.
          break;
        }

        HumanComment parent = optParent.get();
        replyAccounts.add(parent.author.getId());
        visitedUuids.add(current.parentUuid);
        current = parent;
      }
    }
    return replyAccounts;
  }

  private String getCommentLinePrefix(Comment comment) {
    int lineNbr = comment.range == null ? comment.lineNbr : comment.range.startLine;
    StringBuilder sb = new StringBuilder();
    sb.append("PS").append(comment.key.patchSetId);
    if (lineNbr != 0) {
      sb.append(", Line ").append(lineNbr);
    }
    sb.append(": ");
    return sb.toString();
  }

  /**
   * Returns the lines of file content in fileData that are encompassed by range on the given side.
   */
  private List<String> getLinesByRange(Comment.Range range, PatchFile fileData, short side) {
    List<String> lines = new ArrayList<>();

    for (int n = range.startLine; n <= range.endLine; n++) {
      String s = getLine(fileData, side, n);
      if (n == range.startLine && n == range.endLine && range.startChar < range.endChar) {
        s = s.substring(Math.min(range.startChar, s.length()), Math.min(range.endChar, s.length()));
      } else if (n == range.startLine) {
        s = s.substring(Math.min(range.startChar, s.length()));
      } else if (n == range.endLine) {
        s = s.substring(0, Math.min(range.endChar, s.length()));
      }
      lines.add(s);
    }
    return lines;
  }

  /**
   * Get the parent comment of a given comment.
   *
   * @param child the comment with a potential parent comment.
   * @return an optional comment that will be present if the given comment has a parent, and is
   *     empty if it does not.
   */
  private Optional<HumanComment> getParent(Comment child) {
    if (child.parentUuid == null) {
      return Optional.empty();
    }
    Comment.Key key = new Comment.Key(child.parentUuid, child.key.filename, child.key.patchSetId);
    try {
      return commentsUtil.getPublishedHumanComment(changeEmail.getChangeData().notes(), key);
    } catch (StorageException e) {
      logger.atWarning().log("Could not find the parent of this comment: %s", child);
      return Optional.empty();
    }
  }

  /**
   * Retrieve the file lines referred to by a comment.
   *
   * @param comment The comment that refers to some file contents. The comment may be a line comment
   *     or a ranged comment.
   * @param fileData The file on which the comment appears.
   * @return file contents referred to by the comment. If the comment is a line comment, the result
   *     will be a list of one string. Otherwise it will be a list of one or more strings.
   */
  private List<String> getLinesOfComment(Comment comment, PatchFile fileData) {
    List<String> lines = new ArrayList<>();
    if (comment.lineNbr == 0) {
      // file level comment has no line
      return lines;
    }
    if (comment.range == null) {
      lines.add(getLine(fileData, comment.side, comment.lineNbr));
    } else {
      lines.addAll(getLinesByRange(comment.range, fileData, comment.side));
    }
    return lines;
  }

  /**
   * Returns a shortened version of the given comment's message. Will be shortened to 100 characters
   * or the first line, or following the last period within the first 100 characters, whichever is
   * shorter. If the message is shortened, an ellipsis is appended.
   */
  static String getShortenedCommentMessage(String message) {
    int threshold = 100;
    String fullMessage = message.trim();
    String msg = fullMessage;

    if (msg.length() > threshold) {
      msg = msg.substring(0, threshold);
    }

    int lf = msg.indexOf('\n');
    int period = msg.lastIndexOf('.');

    if (lf > 0) {
      // Truncate if a line feed appears within the threshold.
      msg = msg.substring(0, lf);

    } else if (period > 0) {
      // Otherwise truncate if there is a period within the threshold.
      msg = msg.substring(0, period + 1);
    }

    // Append an ellipsis if the message has been truncated.
    if (!msg.equals(fullMessage)) {
      msg += " […]";
    }

    return msg;
  }

  static String getShortenedCommentMessage(Comment comment) {
    return getShortenedCommentMessage(comment.message);
  }

  /**
   * Returns grouped inline comment data mapped to data structures that are suitable for passing
   * into Soy.
   */
  private List<Map<String, Object>> getCommentGroupsTemplateData(Repository repo) {
    List<Map<String, Object>> commentGroups = new ArrayList<>();

    for (CommentChangeEmailDecoratorImpl.FileCommentGroup group : getGroupedInlineComments(repo)) {
      Map<String, Object> groupData = new HashMap<>();
      groupData.put("title", group.getTitle());
      groupData.put("patchSetId", group.patchSetId);

      List<Map<String, Object>> commentsList = new ArrayList<>();
      for (Comment comment : group.comments) {
        Map<String, Object> commentData = new HashMap<>();
        if (group.fileData != null) {
          commentData.put("lines", getLinesOfComment(comment, group.fileData));
        }
        commentData.put("message", comment.message.trim());
        ImmutableList<CommentFormatter.Block> blocks = CommentFormatter.parse(comment.message);
        commentData.put("messageBlocks", commentBlocksToSoyData(blocks));

        // Set the prefix.
        String prefix = getCommentLinePrefix(comment);
        commentData.put("linePrefix", prefix);
        commentData.put("linePrefixEmpty", Strings.padStart(": ", prefix.length(), ' '));

        // Set line numbers.
        int startLine;
        if (comment.range == null) {
          startLine = comment.lineNbr;
        } else {
          startLine = comment.range.startLine;
          commentData.put("endLine", comment.range.endLine);
        }
        commentData.put("startLine", startLine);

        // Set the comment link.

        if (comment.key.filename.equals(Patch.PATCHSET_LEVEL)) {
          commentData.put("link", group.getCommentsTabLink());
        } else {
          commentData.put("link", group.getCommentLink(comment.key.uuid));
        }

        // If the comment has a quote, don't bother loading the parent message.
        if (!hasQuote(blocks)) {
          // Set parent comment info.
          Optional<HumanComment> parent = getParent(comment);
          if (parent.isPresent()) {
            commentData.put("parentMessage", getShortenedCommentMessage(parent.get()));
          }
        }

        commentsList.add(commentData);
      }
      groupData.put("comments", commentsList);

      commentGroups.add(groupData);
    }
    return commentGroups;
  }

  protected List<Map<String, Object>> commentBlocksToSoyData(List<CommentFormatter.Block> blocks) {
    return blocks.stream()
        .map(
            b -> {
              Map<String, Object> map = new HashMap<>();
              switch (b.type) {
                case PARAGRAPH -> {
                  map.put("type", "paragraph");
                  map.put("text", b.text);
                }
                case PRE_FORMATTED -> {
                  map.put("type", "pre");
                  map.put("text", b.text);
                }
                case QUOTE -> {
                  map.put("type", "quote");
                  map.put("quotedBlocks", commentBlocksToSoyData(b.quotedBlocks));
                }
                case LIST -> {
                  map.put("type", "list");
                  map.put("items", b.items);
                }
              }
              return map;
            })
        .collect(toList());
  }

  private boolean hasQuote(List<CommentFormatter.Block> blocks) {
    for (CommentFormatter.Block block : blocks) {
      if (block.type == CommentFormatter.BlockType.QUOTE) {
        return true;
      }
    }
    return false;
  }

  @Nullable
  protected Repository getRepository() {
    try {
      return args.server.openRepository(changeEmail.getProjectState().getNameKey());
    } catch (IOException e) {
      return null;
    }
  }

  @Override
  public void populateEmailContent() {
    changeEmail.addAuthors(RecipientType.TO);

    boolean hasComments;
    try (Repository repo = getRepository()) {
      List<Map<String, Object>> files = getCommentGroupsTemplateData(repo);
      email.addSoyParam("commentFiles", files);
      hasComments = !files.isEmpty();
    }

    email.addSoyParam(
        "patchSetCommentBlocks", commentBlocksToSoyData(CommentFormatter.parse(patchSetComment)));
    email.addSoyParam("labels", getLabelVoteSoyData(labels));
    email.addSoyParam("commentCount", inlineComments.size());
    email.addSoyParam("commentTimestamp", getCommentTimestamp());
    email.addSoyParam(
        "coverLetterBlocks",
        commentBlocksToSoyData(CommentFormatter.parse(changeEmail.getCoverLetter())));

    if (isChangeNoLongerSubmittable()) {
      email.addSoyParam("unsatisfiedSubmitRequirements", formatUnsatisfiedSubmitRequirements());
      email.addSoyParam(
          "oldSubmitRequirements",
          formatSubmitRequirements(preUpdateSubmitRequirementResultsSupplier.get()));
      email.addSoyParam(
          "newSubmitRequirements", formatSubmitRequirements(postUpdateSubmitRequirementResults));
    }

    email.addFooter(MailHeader.COMMENT_DATE.withDelimiter() + getCommentTimestamp());
    email.addFooter(MailHeader.HAS_COMMENTS.withDelimiter() + (hasComments ? "Yes" : "No"));
    email.addFooter(MailHeader.HAS_LABELS.withDelimiter() + (labels.isEmpty() ? "No" : "Yes"));

    for (Account.Id account : getReplyAccounts()) {
      email.addFooter(
          MailHeader.COMMENT_IN_REPLY_TO.withDelimiter() + email.getNameEmailFor(account));
    }

    if (email.getNotify().handling().equals(NotifyHandling.OWNER_REVIEWERS)
        || email.getNotify().handling().equals(NotifyHandling.ALL)) {
      changeEmail.ccAllApprovals();
    }
    if (email.getNotify().handling().equals(NotifyHandling.ALL)) {
      changeEmail.bccStarredBy();
      changeEmail.includeWatchers(
          NotifyType.ALL_COMMENTS,
          !changeEmail.getChange().isWorkInProgress() && !changeEmail.getChange().isPrivate());
    }

    email.appendText(email.textTemplate("Comment"));
    email.appendText(email.textTemplate("CommentFooter"));
    if (email.useHtml()) {
      email.appendHtml(email.soyHtmlTemplate("CommentHtml"));
      email.appendHtml(email.soyHtmlTemplate("CommentFooterHtml"));
    }
  }

  /**
   * Checks whether the change is no longer submittable.
   *
   * @return {@code true} if the change has been submittable before the update and is no longer
   *     submittable after the update has been applied, otherwise {@code false}
   */
  private boolean isChangeNoLongerSubmittable() {
    boolean isSubmittablePreUpdate =
        preUpdateSubmitRequirementResultsSupplier.get().values().stream()
            .allMatch(SubmitRequirementResult::fulfilled);
    logger.atFine().log(
        "the submitability of change %s before the update is %s",
        changeEmail.getChange().getId(), isSubmittablePreUpdate);
    if (!isSubmittablePreUpdate) {
      return false;
    }

    boolean isSubmittablePostUpdate =
        postUpdateSubmitRequirementResults.values().stream()
            .allMatch(SubmitRequirementResult::fulfilled);
    logger.atFine().log(
        "the submitability of change %s after the update is %s",
        changeEmail.getChange().getId(), isSubmittablePostUpdate);
    return !isSubmittablePostUpdate;
  }

  private ImmutableList<String> formatUnsatisfiedSubmitRequirements() {
    return postUpdateSubmitRequirementResults.entrySet().stream()
        .filter(e -> SubmitRequirementResult.Status.UNSATISFIED.equals(e.getValue().status()))
        .map(Map.Entry::getKey)
        .map(SubmitRequirement::name)
        .sorted()
        .collect(toImmutableList());
  }

  private static ImmutableList<String> formatSubmitRequirements(
      Map<SubmitRequirement, SubmitRequirementResult> submitRequirementResults) {
    return submitRequirementResults.entrySet().stream()
        .map(
            e -> {
              if (e.getValue().errorMessage().isPresent()) {
                return String.format(
                    "%s: %s (%s)",
                    e.getKey().name(),
                    e.getValue().status().name(),
                    e.getValue().errorMessage().get());
              }
              return String.format("%s: %s", e.getKey().name(), e.getValue().status().name());
            })
        .sorted()
        .collect(toImmutableList());
  }

  protected String getLine(PatchFile fileInfo, short side, int lineNbr) {
    try {
      return fileInfo.getLine(side, lineNbr);
    } catch (IOException err) {
      // Default to the empty string if the file cannot be safely read.
      logger.atWarning().withCause(err).log("Failed to read file on side %d", side);
      return "";
    } catch (IndexOutOfBoundsException err) {
      // Default to the empty string if the given line number does not appear
      // in the file.
      logger.atFine().withCause(err).log(
          "Failed to get line number %d of file on side %d", lineNbr, side);
      return "";
    } catch (NoSuchEntityException err) {
      // Default to the empty string if the side cannot be found.
      logger.atWarning().withCause(err).log("Side %d of file didn't exist", side);
      return "";
    }
  }

  private ImmutableList<Map<String, Object>> getLabelVoteSoyData(List<LabelVote> votes) {
    ImmutableList.Builder<Map<String, Object>> result = ImmutableList.builder();
    for (LabelVote vote : votes) {
      Map<String, Object> data = new HashMap<>();
      data.put("label", vote.label());

      // Soy needs the short to be cast as an int for it to get converted to the
      // correct tamplate type.
      data.put("value", (int) vote.value());
      result.add(data);
    }
    return result.build();
  }

  protected String getCommentTimestamp() {
    // Grouping is currently done by timestamp.
    return MailProcessingUtil.rfcDateformatter.format(
        ZonedDateTime.ofInstant(changeEmail.getTimestamp(), ZoneId.of("UTC")));
  }
}
