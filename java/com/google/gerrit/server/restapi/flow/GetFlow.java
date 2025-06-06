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

import com.google.gerrit.extensions.common.FlowInfo;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestReadView;
import com.google.inject.Singleton;

/**
 * REST endpoint to get a flow .
 *
 * <p>This REST endpoint handles {@code GET /change/<change-id>/flows/<flow-id>} requests.
 */
@Singleton
public class GetFlow implements RestReadView<FlowResource> {
  @Override
  public Response<FlowInfo> apply(FlowResource flowResource) {
    return Response.ok(FlowJson.format(flowResource.getFlow()));
  }
}
