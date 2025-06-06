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

package com.google.gerrit.server.api.changes;

import static com.google.gerrit.server.api.ApiUtil.asRestApiException;

import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ListMultimap;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.extensions.api.changes.AbandonInput;
import com.google.gerrit.extensions.api.changes.ApplyPatchPatchSetInput;
import com.google.gerrit.extensions.api.changes.AttentionSetApi;
import com.google.gerrit.extensions.api.changes.AttentionSetInput;
import com.google.gerrit.extensions.api.changes.ChangeApi;
import com.google.gerrit.extensions.api.changes.ChangeEditApi;
import com.google.gerrit.extensions.api.changes.ChangeMessageApi;
import com.google.gerrit.extensions.api.changes.Changes;
import com.google.gerrit.extensions.api.changes.CustomKeyedValuesInput;
import com.google.gerrit.extensions.api.changes.FixInput;
import com.google.gerrit.extensions.api.changes.FlowApi;
import com.google.gerrit.extensions.api.changes.HashtagsInput;
import com.google.gerrit.extensions.api.changes.IncludedInInfo;
import com.google.gerrit.extensions.api.changes.MoveInput;
import com.google.gerrit.extensions.api.changes.RebaseInput;
import com.google.gerrit.extensions.api.changes.RestoreInput;
import com.google.gerrit.extensions.api.changes.RevertInput;
import com.google.gerrit.extensions.api.changes.ReviewerApi;
import com.google.gerrit.extensions.api.changes.ReviewerInfo;
import com.google.gerrit.extensions.api.changes.ReviewerInput;
import com.google.gerrit.extensions.api.changes.ReviewerResult;
import com.google.gerrit.extensions.api.changes.RevisionApi;
import com.google.gerrit.extensions.api.changes.SubmittedTogetherInfo;
import com.google.gerrit.extensions.api.changes.SubmittedTogetherOption;
import com.google.gerrit.extensions.api.changes.TopicInput;
import com.google.gerrit.extensions.client.ListChangesOption;
import com.google.gerrit.extensions.common.AccountInfo;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.extensions.common.ChangeInfoDifference;
import com.google.gerrit.extensions.common.ChangeMessageInfo;
import com.google.gerrit.extensions.common.CommentInfo;
import com.google.gerrit.extensions.common.CommitMessageInfo;
import com.google.gerrit.extensions.common.CommitMessageInput;
import com.google.gerrit.extensions.common.FlowInfo;
import com.google.gerrit.extensions.common.FlowInput;
import com.google.gerrit.extensions.common.Input;
import com.google.gerrit.extensions.common.InputWithMessage;
import com.google.gerrit.extensions.common.MergePatchSetInput;
import com.google.gerrit.extensions.common.PureRevertInfo;
import com.google.gerrit.extensions.common.RebaseChainInfo;
import com.google.gerrit.extensions.common.RevertSubmissionInfo;
import com.google.gerrit.extensions.common.SubmitRequirementInput;
import com.google.gerrit.extensions.common.SubmitRequirementResultInfo;
import com.google.gerrit.extensions.common.SuggestedReviewerInfo;
import com.google.gerrit.extensions.common.ValidationOptionInfos;
import com.google.gerrit.extensions.registration.DynamicMap;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.IdString;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.server.DynamicOptions;
import com.google.gerrit.server.change.ChangeMessageResource;
import com.google.gerrit.server.change.ChangeResource;
import com.google.gerrit.server.change.WorkInProgressOp;
import com.google.gerrit.server.restapi.change.Abandon;
import com.google.gerrit.server.restapi.change.AddToAttentionSet;
import com.google.gerrit.server.restapi.change.ApplyPatch;
import com.google.gerrit.server.restapi.change.AttentionSet;
import com.google.gerrit.server.restapi.change.ChangeIncludedIn;
import com.google.gerrit.server.restapi.change.ChangeMessages;
import com.google.gerrit.server.restapi.change.Check;
import com.google.gerrit.server.restapi.change.CheckSubmitRequirement;
import com.google.gerrit.server.restapi.change.CreateMergePatchSet;
import com.google.gerrit.server.restapi.change.DeleteChange;
import com.google.gerrit.server.restapi.change.DeletePrivate;
import com.google.gerrit.server.restapi.change.GetChange;
import com.google.gerrit.server.restapi.change.GetCustomKeyedValues;
import com.google.gerrit.server.restapi.change.GetHashtags;
import com.google.gerrit.server.restapi.change.GetMessage;
import com.google.gerrit.server.restapi.change.GetMetaDiff;
import com.google.gerrit.server.restapi.change.GetPureRevert;
import com.google.gerrit.server.restapi.change.GetTopic;
import com.google.gerrit.server.restapi.change.GetValidationOptions;
import com.google.gerrit.server.restapi.change.Index;
import com.google.gerrit.server.restapi.change.ListChangeComments;
import com.google.gerrit.server.restapi.change.ListChangeDrafts;
import com.google.gerrit.server.restapi.change.ListReviewers;
import com.google.gerrit.server.restapi.change.Move;
import com.google.gerrit.server.restapi.change.PostCustomKeyedValues;
import com.google.gerrit.server.restapi.change.PostHashtags;
import com.google.gerrit.server.restapi.change.PostPrivate;
import com.google.gerrit.server.restapi.change.PostReviewers;
import com.google.gerrit.server.restapi.change.PutMessage;
import com.google.gerrit.server.restapi.change.PutTopic;
import com.google.gerrit.server.restapi.change.Rebase;
import com.google.gerrit.server.restapi.change.RebaseChain;
import com.google.gerrit.server.restapi.change.Restore;
import com.google.gerrit.server.restapi.change.Revert;
import com.google.gerrit.server.restapi.change.RevertSubmission;
import com.google.gerrit.server.restapi.change.Reviewers;
import com.google.gerrit.server.restapi.change.Revisions;
import com.google.gerrit.server.restapi.change.SetReadyForReview;
import com.google.gerrit.server.restapi.change.SetWorkInProgress;
import com.google.gerrit.server.restapi.change.SubmittedTogether;
import com.google.gerrit.server.restapi.change.SuggestChangeReviewers;
import com.google.gerrit.server.restapi.flow.CreateFlow;
import com.google.gerrit.server.restapi.flow.FlowCollection;
import com.google.gerrit.server.restapi.flow.ListFlows;
import com.google.gerrit.util.cli.CmdLineParser;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import com.google.inject.assistedinject.Assisted;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.kohsuke.args4j.CmdLineException;

class ChangeApiImpl implements ChangeApi {
  interface Factory {
    ChangeApiImpl create(ChangeResource change);
  }

  private final Changes changeApi;
  private final Reviewers reviewers;
  private final Revisions revisions;
  private final FlowCollection flowCollection;
  private final ReviewerApiImpl.Factory reviewerApi;
  private final RevisionApiImpl.Factory revisionApi;
  private final ChangeMessageApiImpl.Factory changeMessageApi;
  private final FlowApiImpl.Factory flowApi;
  private final ChangeMessages changeMessages;
  private final SuggestChangeReviewers suggestReviewers;
  private final ListReviewers listReviewers;
  private final ChangeResource change;
  private final Abandon abandon;
  private final Revert revert;
  private final RevertSubmission revertSubmission;
  private final Restore restore;
  private final CreateMergePatchSet updateByMerge;
  private final ApplyPatch applyPatch;
  private final Provider<SubmittedTogether> submittedTogether;
  private final Rebase.CurrentRevision rebase;
  private final RebaseChain rebaseChain;
  private final DeleteChange deleteChange;
  private final GetTopic getTopic;
  private final PutTopic putTopic;
  private final ChangeIncludedIn includedIn;
  private final PostReviewers postReviewers;
  private final Provider<GetChange> getChangeProvider;
  private final Provider<GetMetaDiff> getMetaDiffProvider;
  private final PostHashtags postHashtags;
  private final GetHashtags getHashtags;
  private final PostCustomKeyedValues postCustomKeyedValues;
  private final GetCustomKeyedValues getCustomKeyedValues;
  private final GetValidationOptions getValidationOptions;
  private final AttentionSet attentionSet;
  private final AttentionSetApiImpl.Factory attentionSetApi;
  private final AddToAttentionSet addToAttentionSet;
  private final Provider<ListChangeComments> listCommentsProvider;
  private final Provider<ListChangeDrafts> listDraftsProvider;
  private final ChangeEditApiImpl.Factory changeEditApi;
  private final Check check;
  private final Provider<CheckSubmitRequirement> checkSubmitRequirementProvider;
  private final Index index;
  private final Move move;
  private final PostPrivate postPrivate;
  private final DeletePrivate deletePrivate;
  private final SetWorkInProgress setWip;
  private final SetReadyForReview setReady;
  private final GetMessage getMessage;
  private final PutMessage putMessage;
  private final CreateFlow createFlow;
  private final ListFlows listFlows;
  private final Provider<GetPureRevert> getPureRevertProvider;
  private final DynamicOptionParser dynamicOptionParser;
  private final Injector injector;
  private final DynamicMap<DynamicOptions.DynamicBean> dynamicBeans;

  @Inject
  ChangeApiImpl(
      Changes changeApi,
      Reviewers reviewers,
      Revisions revisions,
      FlowCollection flowCollection,
      ReviewerApiImpl.Factory reviewerApi,
      RevisionApiImpl.Factory revisionApi,
      ChangeMessageApiImpl.Factory changeMessageApi,
      FlowApiImpl.Factory flowApi,
      ChangeMessages changeMessages,
      SuggestChangeReviewers suggestReviewers,
      ListReviewers listReviewers,
      Abandon abandon,
      Revert revert,
      RevertSubmission revertSubmission,
      Restore restore,
      CreateMergePatchSet updateByMerge,
      ApplyPatch applyPatch,
      Provider<SubmittedTogether> submittedTogether,
      Rebase.CurrentRevision rebase,
      RebaseChain rebaseChain,
      DeleteChange deleteChange,
      GetTopic getTopic,
      PutTopic putTopic,
      ChangeIncludedIn includedIn,
      PostReviewers postReviewers,
      Provider<GetChange> getChangeProvider,
      Provider<GetMetaDiff> getMetaDiffProvider,
      PostHashtags postHashtags,
      GetHashtags getHashtags,
      PostCustomKeyedValues postCustomKeyedValues,
      GetCustomKeyedValues getCustomKeyedValues,
      GetValidationOptions getValidationOptions,
      AttentionSet attentionSet,
      AttentionSetApiImpl.Factory attentionSetApi,
      AddToAttentionSet addToAttentionSet,
      Provider<ListChangeComments> listCommentsProvider,
      Provider<ListChangeDrafts> listDraftsProvider,
      ChangeEditApiImpl.Factory changeEditApi,
      Check check,
      Provider<CheckSubmitRequirement> checkSubmitRequirement,
      Index index,
      Move move,
      PostPrivate postPrivate,
      DeletePrivate deletePrivate,
      SetWorkInProgress setWip,
      SetReadyForReview setReady,
      GetMessage getMessage,
      PutMessage putMessage,
      CreateFlow createFlow,
      ListFlows listFlows,
      Provider<GetPureRevert> getPureRevertProvider,
      DynamicOptionParser dynamicOptionParser,
      @Assisted ChangeResource change,
      Injector injector,
      DynamicMap<DynamicOptions.DynamicBean> dynamicBeans) {
    this.changeApi = changeApi;
    this.revert = revert;
    this.revertSubmission = revertSubmission;
    this.reviewers = reviewers;
    this.revisions = revisions;
    this.flowCollection = flowCollection;
    this.reviewerApi = reviewerApi;
    this.revisionApi = revisionApi;
    this.changeMessageApi = changeMessageApi;
    this.flowApi = flowApi;
    this.changeMessages = changeMessages;
    this.suggestReviewers = suggestReviewers;
    this.listReviewers = listReviewers;
    this.abandon = abandon;
    this.restore = restore;
    this.updateByMerge = updateByMerge;
    this.applyPatch = applyPatch;
    this.submittedTogether = submittedTogether;
    this.rebase = rebase;
    this.rebaseChain = rebaseChain;
    this.deleteChange = deleteChange;
    this.getTopic = getTopic;
    this.putTopic = putTopic;
    this.includedIn = includedIn;
    this.postReviewers = postReviewers;
    this.getChangeProvider = getChangeProvider;
    this.getMetaDiffProvider = getMetaDiffProvider;
    this.postHashtags = postHashtags;
    this.getHashtags = getHashtags;
    this.postCustomKeyedValues = postCustomKeyedValues;
    this.getCustomKeyedValues = getCustomKeyedValues;
    this.getValidationOptions = getValidationOptions;
    this.attentionSet = attentionSet;
    this.attentionSetApi = attentionSetApi;
    this.addToAttentionSet = addToAttentionSet;
    this.listCommentsProvider = listCommentsProvider;
    this.listDraftsProvider = listDraftsProvider;
    this.changeEditApi = changeEditApi;
    this.check = check;
    this.checkSubmitRequirementProvider = checkSubmitRequirement;
    this.index = index;
    this.move = move;
    this.postPrivate = postPrivate;
    this.deletePrivate = deletePrivate;
    this.setWip = setWip;
    this.setReady = setReady;
    this.getMessage = getMessage;
    this.putMessage = putMessage;
    this.createFlow = createFlow;
    this.listFlows = listFlows;
    this.getPureRevertProvider = getPureRevertProvider;
    this.dynamicOptionParser = dynamicOptionParser;
    this.change = change;
    this.injector = injector;
    this.dynamicBeans = dynamicBeans;
  }

  @Override
  public String id() {
    return Integer.toString(change.getId().get());
  }

  @Override
  public RevisionApi revision(String id) throws RestApiException {
    try {
      return revisionApi.create(revisions.parse(change, IdString.fromDecoded(id)));
    } catch (Exception e) {
      throw asRestApiException("Cannot parse revision", e);
    }
  }

  @Override
  public ReviewerApi reviewer(String id) throws RestApiException {
    try {
      return reviewerApi.create(reviewers.parse(change, IdString.fromDecoded(id)));
    } catch (Exception e) {
      throw asRestApiException("Cannot parse reviewer", e);
    }
  }

  @Override
  public FlowInfo createFlow(FlowInput flowInput) throws RestApiException {
    try {
      return createFlow.apply(change, flowInput).value();
    } catch (Exception e) {
      throw asRestApiException("Cannot parse reviewer", e);
    }
  }

  @Override
  public FlowApi flow(String flowUuid) throws RestApiException {
    try {
      return flowApi.create(flowCollection.parse(change, IdString.fromDecoded(flowUuid)));
    } catch (Exception e) {
      throw asRestApiException("Cannot parse flow", e);
    }
  }

  @Override
  public List<FlowInfo> flows() throws RestApiException {
    try {
      return listFlows.apply(change).value();
    } catch (Exception e) {
      throw asRestApiException("Cannot list flows", e);
    }
  }

  @Override
  public void abandon(AbandonInput in) throws RestApiException {
    try {
      @SuppressWarnings("unused")
      var unused = abandon.apply(change, in);
    } catch (Exception e) {
      throw asRestApiException("Cannot abandon change", e);
    }
  }

  @Override
  public void restore(RestoreInput in) throws RestApiException {
    try {
      @SuppressWarnings("unused")
      var unused = restore.apply(change, in);
    } catch (Exception e) {
      throw asRestApiException("Cannot restore change", e);
    }
  }

  @Override
  public void move(MoveInput in) throws RestApiException {
    try {
      @SuppressWarnings("unused")
      var unused = move.apply(change, in);
    } catch (Exception e) {
      throw asRestApiException("Cannot move change", e);
    }
  }

  @Override
  public void setPrivate(boolean value, @Nullable String message) throws RestApiException {
    try {
      InputWithMessage input = new InputWithMessage(message);
      if (value) {
        @SuppressWarnings("unused")
        var unused = postPrivate.apply(change, input);
      } else {
        @SuppressWarnings("unused")
        var unused = deletePrivate.apply(change, input);
      }
    } catch (Exception e) {
      throw asRestApiException("Cannot change private status", e);
    }
  }

  @Override
  public void setWorkInProgress(@Nullable String message) throws RestApiException {
    try {
      @SuppressWarnings("unused")
      var unused = setWip.apply(change, new WorkInProgressOp.Input(message));
    } catch (Exception e) {
      throw asRestApiException("Cannot set work in progress state", e);
    }
  }

  @Override
  public void setReadyForReview(@Nullable String message) throws RestApiException {
    try {
      @SuppressWarnings("unused")
      var unused = setReady.apply(change, new WorkInProgressOp.Input(message));
    } catch (Exception e) {
      throw asRestApiException("Cannot set ready for review state", e);
    }
  }

  @Override
  public ChangeApi revert(RevertInput in) throws RestApiException {
    try {
      return changeApi.id(revert.apply(change, in).value()._number);
    } catch (Exception e) {
      throw asRestApiException("Cannot revert change", e);
    }
  }

  @Override
  public RevertSubmissionInfo revertSubmission(RevertInput in) throws RestApiException {
    try {
      return revertSubmission.apply(change, in).value();
    } catch (Exception e) {
      throw asRestApiException("Cannot revert a change submission", e);
    }
  }

  @Override
  public ChangeInfo createMergePatchSet(MergePatchSetInput in) throws RestApiException {
    try {
      return updateByMerge.apply(change, in).value();
    } catch (Exception e) {
      throw asRestApiException("Cannot update change by merge", e);
    }
  }

  @Override
  public ChangeInfo applyPatch(ApplyPatchPatchSetInput in) throws RestApiException {
    try {
      return applyPatch.apply(change, in).value();
    } catch (Exception e) {
      throw asRestApiException("Cannot apply patch", e);
    }
  }

  @Override
  public SubmittedTogetherInfo submittedTogether(
      EnumSet<ListChangesOption> listOptions, EnumSet<SubmittedTogetherOption> submitOptions)
      throws RestApiException {
    try {
      return submittedTogether
          .get()
          .addListChangesOption(listOptions)
          .addSubmittedTogetherOption(submitOptions)
          .applyInfo(change);
    } catch (Exception e) {
      throw asRestApiException("Cannot query submittedTogether", e);
    }
  }

  @Override
  public void rebase(RebaseInput in) throws RestApiException {
    try {
      @SuppressWarnings("unused")
      var unused = rebase.apply(change, in);
    } catch (Exception e) {
      throw asRestApiException("Cannot rebase change", e);
    }
  }

  @Override
  public Response<RebaseChainInfo> rebaseChain(RebaseInput in) throws RestApiException {
    try {
      return rebaseChain.apply(change, in);
    } catch (Exception e) {
      throw asRestApiException("Cannot rebase chain", e);
    }
  }

  @Override
  public void delete() throws RestApiException {
    try {
      @SuppressWarnings("unused")
      var unused = deleteChange.apply(change, null);
    } catch (Exception e) {
      throw asRestApiException("Cannot delete change", e);
    }
  }

  @Override
  public String topic() throws RestApiException {
    try {
      return getTopic.apply(change).value();
    } catch (Exception e) {
      throw asRestApiException("Cannot get topic", e);
    }
  }

  @Override
  public void topic(String topic) throws RestApiException {
    TopicInput in = new TopicInput();
    in.topic = topic;
    try {
      @SuppressWarnings("unused")
      var unused = putTopic.apply(change, in);
    } catch (Exception e) {
      throw asRestApiException("Cannot set topic", e);
    }
  }

  @Override
  public IncludedInInfo includedIn() throws RestApiException {
    try {
      return includedIn.apply(change).value();
    } catch (Exception e) {
      throw asRestApiException("Could not extract IncludedIn data", e);
    }
  }

  @Override
  public ReviewerResult addReviewer(ReviewerInput in) throws RestApiException {
    try {
      return postReviewers.apply(change, in).value();
    } catch (Exception e) {
      throw asRestApiException("Cannot add change reviewer", e);
    }
  }

  @Override
  public SuggestedReviewersRequest suggestReviewers() throws RestApiException {
    return new SuggestedReviewersRequest() {
      @Override
      public List<SuggestedReviewerInfo> get() throws RestApiException {
        return ChangeApiImpl.this.suggestReviewers(this);
      }
    };
  }

  private List<SuggestedReviewerInfo> suggestReviewers(SuggestedReviewersRequest r)
      throws RestApiException {
    try {
      suggestReviewers.setQuery(r.getQuery());
      suggestReviewers.setLimit(r.getLimit());
      suggestReviewers.setExcludeGroups(r.getExcludeGroups());
      suggestReviewers.setReviewerState(r.getReviewerState());
      return suggestReviewers.apply(change).value();
    } catch (Exception e) {
      throw asRestApiException("Cannot retrieve suggested reviewers", e);
    }
  }

  @Override
  public List<ReviewerInfo> reviewers() throws RestApiException {
    try {
      return listReviewers.apply(change).value();
    } catch (Exception e) {
      throw asRestApiException("Cannot retrieve reviewers", e);
    }
  }

  @Override
  public ChangeInfo get(
      EnumSet<ListChangesOption> options, ImmutableListMultimap<String, String> pluginOptions)
      throws RestApiException {
    try (DynamicOptions dynamicOptions = new DynamicOptions(injector, dynamicBeans)) {
      GetChange getChange = getChangeProvider.get();
      options.forEach(getChange::addOption);
      dynamicOptionParser.parseDynamicOptions(getChange, pluginOptions, dynamicOptions);
      return getChange.apply(change).value();
    } catch (Exception e) {
      throw asRestApiException("Cannot retrieve change", e);
    }
  }

  @Override
  public ChangeInfoDifference metaDiff(
      @Nullable String oldMetaRevId,
      @Nullable String newMetaRevId,
      EnumSet<ListChangesOption> options,
      ImmutableListMultimap<String, String> pluginOptions)
      throws RestApiException {
    try (DynamicOptions dynamicOptions = new DynamicOptions(injector, dynamicBeans)) {
      GetMetaDiff metaDiff = getMetaDiffProvider.get();
      metaDiff.setOldMetaRevId(oldMetaRevId);
      metaDiff.setNewMetaRevId(newMetaRevId);
      options.forEach(metaDiff::addOption);
      dynamicOptionParser.parseDynamicOptions(metaDiff, pluginOptions, dynamicOptions);
      return metaDiff.apply(change).value();
    } catch (Exception e) {
      throw asRestApiException("Cannot retrieve metaDiff", e);
    }
  }

  @Override
  public ChangeEditApi edit() throws RestApiException {
    return changeEditApi.create(change);
  }

  @Override
  public CommitMessageInfo getMessage() throws RestApiException {
    try {
      return getMessage.apply(change).value();
    } catch (Exception e) {
      throw asRestApiException("Cannot get message", e);
    }
  }

  @Override
  public void setMessage(CommitMessageInput in) throws RestApiException {
    try {
      @SuppressWarnings("unused")
      var unused = putMessage.apply(change, in);
    } catch (Exception e) {
      throw asRestApiException("Cannot edit commit message", e);
    }
  }

  @Override
  public void setHashtags(HashtagsInput input) throws RestApiException {
    try {
      @SuppressWarnings("unused")
      var unused = postHashtags.apply(change, input);
    } catch (Exception e) {
      throw asRestApiException("Cannot post hashtags", e);
    }
  }

  @Override
  public Set<String> getHashtags() throws RestApiException {
    try {
      return getHashtags.apply(change).value();
    } catch (Exception e) {
      throw asRestApiException("Cannot get hashtags", e);
    }
  }

  @Override
  public void setCustomKeyedValues(CustomKeyedValuesInput input) throws RestApiException {
    try {
      @SuppressWarnings("unused")
      var unused = postCustomKeyedValues.apply(change, input);
    } catch (Exception e) {
      throw asRestApiException("Cannot post custom keyed values", e);
    }
  }

  @Override
  public ImmutableMap<String, String> getCustomKeyedValues() throws RestApiException {
    try {
      return getCustomKeyedValues.apply(change).value();
    } catch (Exception e) {
      throw asRestApiException("Cannot get custom keyed values", e);
    }
  }

  @Override
  public ValidationOptionInfos getValidationOptions() throws RestApiException {
    try {
      return getValidationOptions.apply(change).value();
    } catch (Exception e) {
      throw asRestApiException("Cannot get validation options", e);
    }
  }

  @Override
  public AccountInfo addToAttentionSet(AttentionSetInput input) throws RestApiException {
    try {
      return addToAttentionSet.apply(change, input).value();
    } catch (Exception e) {
      throw asRestApiException("Cannot add to attention set", e);
    }
  }

  @Override
  public AttentionSetApi attention(String id) throws RestApiException {
    try {
      return attentionSetApi.create(attentionSet.parse(change, IdString.fromDecoded(id)));
    } catch (Exception e) {
      throw asRestApiException("Cannot parse account", e);
    }
  }

  @Override
  public CommentsRequest commentsRequest() {
    return new CommentsRequest() {
      @Override
      public Map<String, List<CommentInfo>> get() throws RestApiException {
        try {
          ListChangeComments listComments = listCommentsProvider.get();
          listComments.setContext(this.getContext());
          listComments.setContextPadding(this.getContextPadding());
          return listComments.apply(change).value();
        } catch (Exception e) {
          throw asRestApiException("Cannot get comments", e);
        }
      }

      @Override
      public List<CommentInfo> getAsList() throws RestApiException {
        try {
          ListChangeComments listComments = listCommentsProvider.get();
          listComments.setContext(this.getContext());
          listComments.setContextPadding(this.getContextPadding());
          return listComments.getComments(change);
        } catch (Exception e) {
          throw asRestApiException("Cannot get comments", e);
        }
      }
    };
  }

  @Override
  public DraftsRequest draftsRequest() {
    return new DraftsRequest() {
      @Override
      public Map<String, List<CommentInfo>> get() throws RestApiException {
        try {
          ListChangeDrafts listDrafts = listDraftsProvider.get();
          listDrafts.setContext(this.getContext());
          listDrafts.setContextPadding(this.getContextPadding());
          return listDrafts.apply(change).value();
        } catch (Exception e) {
          throw asRestApiException("Cannot get drafts", e);
        }
      }

      @Override
      public List<CommentInfo> getAsList() throws RestApiException {
        try {
          ListChangeDrafts listDrafts = listDraftsProvider.get();
          listDrafts.setContext(this.getContext());
          listDrafts.setContextPadding(this.getContextPadding());
          return listDrafts.getComments(change);
        } catch (Exception e) {
          throw asRestApiException("Cannot get drafts", e);
        }
      }
    };
  }

  @Override
  public ChangeInfo check() throws RestApiException {
    try {
      return check.apply(change).value();
    } catch (Exception e) {
      throw asRestApiException("Cannot check change", e);
    }
  }

  @Override
  public ChangeInfo check(FixInput fix) throws RestApiException {
    try {
      // TODO(dborowitz): Convert to RetryingRestModifyView. Needs to plumb BatchUpdate.Factory into
      // ConsistencyChecker.
      return check.apply(change, fix).value();
    } catch (Exception e) {
      throw asRestApiException("Cannot check change", e);
    }
  }

  @Override
  public CheckSubmitRequirementRequest checkSubmitRequirementRequest() {
    return new CheckSubmitRequirementRequest() {
      @Override
      public SubmitRequirementResultInfo get() throws RestApiException {
        try {
          CheckSubmitRequirement check = checkSubmitRequirementProvider.get();
          check.setSrName(this.srName());
          check.setRefsConfigChangeId(this.getRefsConfigChangeId());
          return check.apply(change, null).value();
        } catch (Exception e) {
          throw asRestApiException("Cannot check submit requirement", e);
        }
      }
    };
  }

  @Override
  public SubmitRequirementResultInfo checkSubmitRequirement(SubmitRequirementInput input)
      throws RestApiException {
    try {
      return checkSubmitRequirementProvider.get().apply(change, input).value();
    } catch (Exception e) {
      throw asRestApiException("Cannot check submit requirement", e);
    }
  }

  @Override
  public void index() throws RestApiException {
    try {
      @SuppressWarnings("unused")
      var unused = index.apply(change, new Input());
    } catch (Exception e) {
      throw asRestApiException("Cannot index change", e);
    }
  }

  @Override
  public PureRevertInfo pureRevert() throws RestApiException {
    return pureRevert(null);
  }

  @Override
  public PureRevertInfo pureRevert(@Nullable String claimedOriginal) throws RestApiException {
    try {
      GetPureRevert getPureRevert = getPureRevertProvider.get();
      getPureRevert.setClaimedOriginal(claimedOriginal);
      return getPureRevert.apply(change).value();
    } catch (Exception e) {
      throw asRestApiException("Cannot compute pure revert", e);
    }
  }

  @Override
  public List<ChangeMessageInfo> messages() throws RestApiException {
    try {
      return changeMessages.list().apply(change).value();
    } catch (Exception e) {
      throw asRestApiException("Cannot list change messages", e);
    }
  }

  @Override
  public ChangeMessageApi message(String id) throws RestApiException {
    try {
      ChangeMessageResource resource = changeMessages.parse(change, IdString.fromDecoded(id));
      return changeMessageApi.create(resource);
    } catch (Exception e) {
      throw asRestApiException("Cannot parse change message " + id, e);
    }
  }

  @Singleton
  static class DynamicOptionParser {
    private final CmdLineParser.Factory cmdLineParserFactory;

    @Inject
    DynamicOptionParser(CmdLineParser.Factory cmdLineParserFactory) {
      this.cmdLineParserFactory = cmdLineParserFactory;
    }

    void parseDynamicOptions(
        Object bean, ListMultimap<String, String> pluginOptions, DynamicOptions dynamicOptions)
        throws BadRequestException {
      CmdLineParser clp = cmdLineParserFactory.create(bean);
      dynamicOptions.setBean(bean);
      dynamicOptions.startLifecycleListeners();
      dynamicOptions.parseDynamicBeans(clp);
      dynamicOptions.setDynamicBeans();
      dynamicOptions.onBeanParseStart();
      try {
        clp.parseOptionMap(pluginOptions);
      } catch (CmdLineException | NumberFormatException e) {
        throw new BadRequestException(e.getMessage(), e);
      }
      dynamicOptions.onBeanParseEnd();
    }
  }
}
