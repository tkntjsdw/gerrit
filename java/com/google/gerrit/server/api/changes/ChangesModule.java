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

import com.google.gerrit.extensions.api.changes.Changes;
import com.google.gerrit.extensions.config.FactoryModule;

public class ChangesModule extends FactoryModule {
  @Override
  protected void configure() {
    bind(Changes.class).to(ChangesImpl.class);

    factory(ChangeApiImpl.Factory.class);
    factory(CommentApiImpl.Factory.class);
    factory(DraftApiImpl.Factory.class);
    factory(RevisionApiImpl.Factory.class);
    factory(FileApiImpl.Factory.class);
    factory(ReviewerApiImpl.Factory.class);
    factory(RevisionReviewerApiImpl.Factory.class);
    factory(ChangeEditApiImpl.Factory.class);
    factory(ChangeMessageApiImpl.Factory.class);
    factory(AttentionSetApiImpl.Factory.class);
    factory(FlowApiImpl.Factory.class);
  }
}
