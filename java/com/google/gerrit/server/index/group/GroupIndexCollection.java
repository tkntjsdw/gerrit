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

package com.google.gerrit.server.index.group;

import com.google.common.annotations.VisibleForTesting;
import com.google.gerrit.entities.AccountGroup;
import com.google.gerrit.entities.InternalGroup;
import com.google.gerrit.index.IndexCollection;
import com.google.gerrit.metrics.MetricMaker;
import com.google.inject.Inject;
import com.google.inject.Singleton;

/** Collection of active group indices. See {@link IndexCollection} for details on collections. */
@Singleton
public class GroupIndexCollection
    extends IndexCollection<AccountGroup.UUID, InternalGroup, GroupIndex> {
  @Inject
  @VisibleForTesting
  public GroupIndexCollection(MetricMaker metrics) {
    super(metrics);
  }

  @Override
  protected IndexType getIndexName() {
    return IndexType.GROUPS;
  }
}
