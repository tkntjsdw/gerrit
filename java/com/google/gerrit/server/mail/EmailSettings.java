// Copyright (C) 2012 The Android Open Source Project
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

package com.google.gerrit.server.mail;

import com.google.gerrit.server.config.GerritServerConfig;
import com.google.gerrit.server.mail.receive.Protocol;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.util.concurrent.TimeUnit;
import org.eclipse.jgit.lib.Config;

@Singleton
public class EmailSettings {
  private static final String SEND_EMAIL = "sendemail";
  private static final String RECEIVE_EMAIL = "receiveemail";
  // Send
  public final boolean html;
  public final boolean includeDiff;
  public final int maximumDiffSize;
  // Receive
  public final Protocol protocol;
  public final String host;
  public final int port;
  public final String username;
  public final String password;
  public final Encryption encryption;
  public final long fetchInterval; // in milliseconds
  public final boolean sendNewPatchsetEmails;
  public final boolean includeThreadIndexHeader;

  @Inject
  EmailSettings(@GerritServerConfig Config cfg) {
    // Send
    html = cfg.getBoolean(SEND_EMAIL, "html", true);
    includeDiff = cfg.getBoolean(SEND_EMAIL, "includeDiff", false);
    maximumDiffSize = cfg.getInt(SEND_EMAIL, "maximumDiffSize", 256 << 10);
    // Receive
    protocol = cfg.getEnum(RECEIVE_EMAIL, null, "protocol", Protocol.NONE);
    host = cfg.getString(RECEIVE_EMAIL, null, "host");
    port = cfg.getInt(RECEIVE_EMAIL, "port", 0);
    username = cfg.getString(RECEIVE_EMAIL, null, "username");
    password = cfg.getString(RECEIVE_EMAIL, null, "password");
    encryption = cfg.getEnum(RECEIVE_EMAIL, null, "encryption", Encryption.NONE);
    fetchInterval =
        cfg.getTimeUnit(
            RECEIVE_EMAIL,
            null,
            "fetchInterval",
            TimeUnit.MILLISECONDS.convert(60, TimeUnit.SECONDS),
            TimeUnit.MILLISECONDS);
    sendNewPatchsetEmails = cfg.getBoolean("change", null, "sendNewPatchsetEmails", true);
    includeThreadIndexHeader = cfg.getBoolean(SEND_EMAIL, null, "includeThreadIndexHeader", true);
  }
}
