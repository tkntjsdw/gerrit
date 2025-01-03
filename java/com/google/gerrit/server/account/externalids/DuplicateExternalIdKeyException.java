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

package com.google.gerrit.server.account.externalids;

import com.google.gerrit.exceptions.DuplicateKeyException;

/**
 * Exception that is thrown if an external ID cannot be inserted because an external ID with the
 * same key already exists.
 */
public class DuplicateExternalIdKeyException extends DuplicateKeyException {
  private static final long serialVersionUID = 1L;

  private final ExternalId.Key duplicateKey;

  public DuplicateExternalIdKeyException(ExternalId.Key duplicateKey) {
    super("Duplicate external ID key: " + duplicateKey.get());
    this.duplicateKey = duplicateKey;
  }

  public DuplicateExternalIdKeyException(ExternalId.Key duplicateKey, Throwable why) {
    super("Duplicate external ID key: " + duplicateKey.get(), why);
    this.duplicateKey = duplicateKey;
  }

  public ExternalId.Key getDuplicateKey() {
    return duplicateKey;
  }
}
