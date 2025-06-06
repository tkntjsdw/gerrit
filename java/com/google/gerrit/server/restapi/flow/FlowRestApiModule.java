// Copyright (C) 2025 The Android Open Source Project
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

package com.google.gerrit.server.restapi.flow;

import static com.google.gerrit.server.change.ChangeResource.CHANGE_KIND;
import static com.google.gerrit.server.restapi.flow.FlowResource.FLOW_KIND;

import com.google.gerrit.extensions.registration.DynamicMap;
import com.google.gerrit.extensions.restapi.RestApiModule;

/** Guice module to bind the flow REST API. */
public class FlowRestApiModule extends RestApiModule {
  @Override
  protected void configure() {
    bind(FlowCollection.class);

    DynamicMap.mapOf(binder(), FLOW_KIND);

    child(CHANGE_KIND, "flows").to(FlowCollection.class);
    postOnCollection(FLOW_KIND).to(CreateFlow.class);

    get(FLOW_KIND).to(GetFlow.class);
    delete(FLOW_KIND).to(DeleteFlow.class);
  }
}
