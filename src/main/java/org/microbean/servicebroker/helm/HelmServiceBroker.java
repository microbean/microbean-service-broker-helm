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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;

import java.nio.file.Files;
import java.nio.file.Path;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.enterprise.context.ApplicationScoped;

import javax.enterprise.inject.Instance;

import javax.enterprise.inject.spi.BeanManager;

import javax.inject.Inject;

import org.microbean.servicebroker.api.ServiceBroker;
import org.microbean.servicebroker.api.ServiceBrokerException;

import org.microbean.servicebroker.api.command.NoSuchServiceInstanceException;
import org.microbean.servicebroker.api.command.ServiceInstanceAlreadyExistsException;

import org.microbean.servicebroker.api.query.state.Binding;
import org.microbean.servicebroker.api.query.state.Catalog;
import org.microbean.servicebroker.api.query.state.Plan;
import org.microbean.servicebroker.api.query.state.Service;
import org.microbean.servicebroker.api.query.state.ServiceInstance;

import org.microbean.servicebroker.api.command.ProvisionServiceInstanceCommand;
import org.microbean.servicebroker.api.command.ProvisionBindingCommand;
import org.microbean.servicebroker.api.command.UpdateServiceInstanceCommand;
import org.microbean.servicebroker.api.command.DeleteServiceInstanceCommand;
import org.microbean.servicebroker.api.command.DeleteBindingCommand;

import org.microbean.servicebroker.helm.annotation.Chart;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.yaml.snakeyaml.Yaml;

@ApplicationScoped
public class HelmServiceBroker extends ServiceBroker {

  private static final String LS = System.getProperty("line.separator", "\n");

  private final Logger logger;
  
  private final Helm helm;

  private final BeanManager beanManager;
  
  @Inject
  public HelmServiceBroker(final Helm helm, final BeanManager beanManager) {
    super();
    this.logger = LoggerFactory.getLogger(this.getClass());
    assert this.logger != null;
    this.helm = helm;
    this.beanManager = beanManager;
  }

  @Override
  public Catalog getCatalog() throws ServiceBrokerException {
    if (logger.isTraceEnabled()) {
      logger.trace("ENTRY");
    }
    Catalog catalog = null;    
    Set<String> results = null;
    try {
      results = this.helm.search();
    } catch (final HelmException helmException) {
      throw new ServiceBrokerException(helmException);
    }
    if (results != null && !results.isEmpty()) {
      final Set<Service> services = new LinkedHashSet<>();
      for (final String serviceId : results) {
        final Plan freePlan = new Plan("free:" + serviceId,
                                       "free:" + serviceId,
                                       "Description goes here for free:" + serviceId,
                                       null /* no metadata */,
                                       true /* free */,
                                       null /* pick up bindable information from the containing service */);
        final Service service = new Service(serviceId,
                                            serviceId,
                                            "Description goes here for " + serviceId,
                                            null /* no tags */,
                                            null /* no requires */,
                                            true /* bindable */,
                                            null /* no metadata */,
                                            null /* no dashboardClient */,
                                            false /* not updatable */,
                                            Collections.singleton(freePlan));
        services.add(service);
      }
      catalog = new Catalog(services);
    }
    if (logger.isTraceEnabled()) {
      logger.trace("EXIT {}", catalog);
    }
    return catalog;
  }

  @Override
  public ProvisionServiceInstanceCommand.Response execute(final ProvisionServiceInstanceCommand command) throws ServiceBrokerException {
    if (logger.isTraceEnabled()) {
      logger.trace("ENTRY {}", command);
    }
    ProvisionServiceInstanceCommand.Response returnValue = null;
    if (command != null) {
      final Map<? extends String, ?> parameters = command.getParameters();
      final Collection<? extends Path> valueFiles;
      final Path temporaryValuePath;
      if (parameters == null || parameters.isEmpty()) {
        temporaryValuePath = null;
        valueFiles = null;
      } else {
        Path p = null;
        try {
          p = toTemporaryValuePath(parameters);
        } catch (final IOException ioException) {
          throw new ServiceBrokerException(ioException.getMessage(), ioException);
        } finally {
          temporaryValuePath = p;
        }
        if (temporaryValuePath == null) {
          valueFiles = null;
        } else {
          valueFiles = Collections.singleton(temporaryValuePath);
        }
      }
      Helm.Status status = null;
      try {
        status = this.helm.install(command.getServiceId(), /* chartName, e.g. stable/foobar */
                                   command.getInstanceId(), /* releaseName e.g. foobar */
                                   null, /* releaseTemplateName */
                                   null, /* namespace */
                                   false, /* noHooks = false, therefore hooks */
                                   false, /* replace */
                                   valueFiles, /* valueFiles */
                                   false, /* verify */
                                   null /* version */ );
      } catch (final DuplicateReleaseException duplicateReleaseException) {
        returnValue = new ProvisionServiceInstanceCommand.Response();
        throw new ServiceInstanceAlreadyExistsException(command.getInstanceId(), duplicateReleaseException, returnValue);
      } catch (final HelmException helmException) {
        throw new ServiceBrokerException(helmException);
      }
      returnValue = new ProvisionServiceInstanceCommand.Response();
    }
    if (logger.isTraceEnabled()) {
      logger.trace("EXIT {}", returnValue);
    }
    return returnValue;
  }

  @Override
  public UpdateServiceInstanceCommand.Response execute(final UpdateServiceInstanceCommand command) throws ServiceBrokerException {
    if (logger.isTraceEnabled()) {
      logger.trace("ENTRY {}, command");
    }
    throw new ServiceBrokerException("Unimplemented; services are not (yet?) updatable");
  }

  @Override
  public DeleteServiceInstanceCommand.Response execute(final DeleteServiceInstanceCommand command) throws ServiceBrokerException {
    if (logger.isTraceEnabled()) {
      logger.trace("ENTRY {}", command);
    }
    DeleteServiceInstanceCommand.Response returnValue = new DeleteServiceInstanceCommand.Response();
    if (command != null) {
      try {
        this.helm.delete(command.getInstanceId(), true);
      } catch (final NoSuchReleaseException noSuchReleaseException) {
        throw new NoSuchServiceInstanceException(command.getInstanceId(), noSuchReleaseException, returnValue);
      } catch (final HelmException helmException) {
        throw new ServiceBrokerException(helmException);
      }
    }
    if (logger.isTraceEnabled()) {
      logger.trace("EXIT {}", returnValue);
    }
    return returnValue;
  }

  @Override
  public ProvisionBindingCommand.Response execute(final ProvisionBindingCommand command) throws ServiceBrokerException {
    if (logger.isTraceEnabled()) {
      logger.trace("ENTRY {}", command);
    }
    ProvisionBindingCommand.Response returnValue = null;
    Map<? extends String, ?> credentials = null;
    if (command != null) {
      final String instanceId = command.getServiceInstanceId();
      Helm.Status status = null;
      try {
        status = this.helm.status(instanceId);
      } catch (final NoSuchReleaseException noSuchReleaseException) {
        throw new NoSuchServiceInstanceException(instanceId, noSuchReleaseException);
      } catch (final HelmException helmException) {
        throw new ServiceBrokerException(helmException);
      }
      if (status != null) {
        String chartName = command.getServiceId(); // bleagh; like stable/fred; we just want fred
        if (chartName != null) {
          final CredentialsExtractor credentialsExtractor = this.getCredentialsExtractor(chartName);
          if (credentialsExtractor != null) {
            credentials = credentialsExtractor.extractCredentials(status);
          }
        }
      }
    }
    if (returnValue == null) {
      returnValue = new ProvisionBindingCommand.Response(credentials);
    }
    if (logger.isTraceEnabled()) {
      logger.trace("EXIT {}", returnValue);
    }
    return returnValue;
  }

  @Override
  public DeleteBindingCommand.Response execute(final DeleteBindingCommand command) throws ServiceBrokerException {
    if (logger.isTraceEnabled()) {
      logger.trace("ENTRY {}", command);
    }
    throw new ServiceBrokerException("Unimplemented; bindings are not (yet?) deletable");
  }

  @Override
  public ServiceInstance getServiceInstance(final String instanceId) throws ServiceBrokerException {
    if (logger.isTraceEnabled()) {
      logger.trace("ENTRY {}", instanceId);
    }
    ServiceInstance returnValue = null;
    if (instanceId != null) {
      Helm.Status status = null;
      try {
        status = this.helm.status(instanceId);
      } catch (final NoSuchReleaseException noSuchReleaseException) {
        throw new NoSuchServiceInstanceException(instanceId, noSuchReleaseException);
      } catch (final HelmException helmException) {
        throw new ServiceBrokerException(helmException);
      }
      returnValue = new HelmServiceInstance(null /* no dashboard */, status);
    }
    if (logger.isTraceEnabled()) {
      logger.trace("EXIT {}", returnValue);
    }
    return returnValue;
  }

  @Override
  public Binding getBinding(final String serviceInstanceId, final String bindingId) throws ServiceBrokerException {
    if (logger.isTraceEnabled()) {
      logger.trace("ENTRY {}, {}", serviceInstanceId, bindingId);
    }
    Binding returnValue = null;
    Helm.Status status = null;
    Map<? extends String, ?> credentials = null;
    if (serviceInstanceId != null) {
      String chartName = null;
      try {
        chartName = this.getChartName(serviceInstanceId);
      } catch (final HelmException helmException) {
        throw new ServiceBrokerException(helmException);
      }
      final CredentialsExtractor credentialsExtractor = this.getCredentialsExtractor(chartName);
      if (credentialsExtractor != null) {
        try {
          status = this.helm.status(serviceInstanceId);
        } catch (final NoSuchReleaseException noSuchReleaseException) {
          throw new NoSuchServiceInstanceException(serviceInstanceId, noSuchReleaseException);
        } catch (final HelmException helmException) {
          throw new ServiceBrokerException(helmException);
        }
        if (status != null) {
          credentials = credentialsExtractor.extractCredentials(status);
        }
      }
    }
    returnValue = new HelmBinding(credentials, status);
    if (logger.isTraceEnabled()) {
      logger.trace("EXIT {}", returnValue);
    }
    return returnValue;
  }

  private final String getChartName(final String serviceInstanceId) throws HelmException {
    if (logger.isTraceEnabled()) {
      logger.trace("ENTRY {}", serviceInstanceId);
    }
    String chartName = null;
    if (serviceInstanceId != null) {
      final List<String> listing = this.helm.list(false,
                                                  true, // byDate
                                                  false,
                                                  false,
                                                  true, // deployed
                                                  false,
                                                  1, // max
                                                  null,
                                                  true, // reverse
                                                  true, // quiet
                                                  new StringBuilder("^").append(serviceInstanceId).append("$").toString());
      if (listing == null || listing.size() != 1) {
        throw new HelmException("Unexpected helm list: " + listing, new IllegalStateException("Unexpected helm list: " + listing));
      }
      chartName = listing.get(0);
      if (chartName == null || chartName.isEmpty()) {
        throw new HelmException("Unexpected helm list: " + listing, new IllegalStateException("Unexpected helm list: " + listing));
      }
    }
    if (logger.isTraceEnabled()) {
      logger.trace("EXIT {}", chartName);
    }
    return chartName;
  }
  
  private final CredentialsExtractor getCredentialsExtractor(String chartName) {
    if (logger.isTraceEnabled()) {
      logger.trace("ENTRY {}", chartName);
    }
    CredentialsExtractor returnValue = null;
    if (chartName != null && this.beanManager != null) {
      
      Instance<CredentialsExtractor> credentialsExtractorInstance = beanManager.createInstance().select(CredentialsExtractor.class, new Chart.Literal(chartName));
      assert credentialsExtractorInstance != null;
      if (credentialsExtractorInstance.isUnsatisfied() && chartName.length() > "/".length()) {
        final int slashIndex = chartName.indexOf("/");
        if (slashIndex > 0) {
          credentialsExtractorInstance = beanManager.createInstance().select(CredentialsExtractor.class, new Chart.Literal(chartName.substring(slashIndex + 1)));
        }
      }
      if (!credentialsExtractorInstance.isUnsatisfied()) {
        returnValue = credentialsExtractorInstance.get();
      }
    }
    if (logger.isTraceEnabled()) {
      logger.trace("EXIT {}", returnValue);
    }
    return returnValue;
  }

  private final Path toTemporaryValuePath(final Map<?, ?> map) throws IOException {
    if (logger.isTraceEnabled()) {
      logger.trace("ENTRY {}", map);
    }
    Path returnValue = null;
    if (map != null && !map.isEmpty()) {
      returnValue = Files.createTempFile("HelmServiceBroker-", ".yaml");
      assert returnValue != null;
      try {
        new Yaml().dump(map, Files.newBufferedWriter(returnValue));
      } catch (final RuntimeException oops) {
        try {
          Files.deleteIfExists(returnValue);
        } catch (final IOException ioException) {
          oops.addSuppressed(ioException);
        }
        throw oops;
      }
    }
    if (logger.isTraceEnabled()) {
      logger.trace("EXIT {}", returnValue);
    }
    return returnValue;
  }
  
}
