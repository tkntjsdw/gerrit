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

package com.google.gerrit.server.project;

import com.google.common.util.concurrent.Striped;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.inject.AbstractModule;
import com.google.inject.Singleton;
import java.util.concurrent.locks.Lock;

/** In-memory lock manager */
@Singleton
public class DefaultLockManager implements LockManager {

  public static class DefaultLockManagerModule extends AbstractModule {
    @Override
    protected void configure() {
      DynamicItem.bind(binder(), LockManager.class).to(DefaultLockManager.class);
    }
  }

  Striped<Lock> locks = Striped.lock(10);

  @Override
  public Lock getLock(String name) {
    return locks.get(name);
  }
}
