/* -*- mode: Java; c-basic-offset: 2; indent-tabs-mode: nil; coding: utf-8-unix -*-
 *
 * Copyright © 2017 MicroBean.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */
package org.microbean.servicebroker.helm;

import java.net.URI;

import org.microbean.servicebroker.api.query.state.ServiceInstance;

public class HelmServiceInstance extends ServiceInstance {

  private final Helm.Status status;
  
  public HelmServiceInstance() {
    this(null, null);
  }

  public HelmServiceInstance(final URI dashboardUri) {
    this(null, null);
  }

  public HelmServiceInstance(final URI dashboardUri, final Helm.Status status) {
    super(dashboardUri);
    this.status = status;
  }

  public Helm.Status getStatus() {
    return this.status;
  }
  
}
