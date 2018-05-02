/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.config.internal;

import static java.util.Collections.emptyMap;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static org.mule.runtime.core.api.config.bootstrap.ArtifactType.APP;
import static org.mule.runtime.deployment.model.internal.application.MuleApplicationClassLoader.resolveContextArtifactPluginClassLoaders;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.mule.runtime.api.artifact.ast.ArtifactAst;
import org.mule.runtime.api.component.ConfigurationProperties;
import org.mule.runtime.api.i18n.I18nMessageFactory;
import org.mule.runtime.api.lifecycle.Startable;
import org.mule.runtime.app.declaration.api.ArtifactDeclaration;
import org.mule.runtime.config.internal.artifact.SpringArtifactContext;
import org.mule.runtime.core.api.MuleContext;
import org.mule.runtime.core.api.config.ConfigResource;
import org.mule.runtime.core.api.config.ConfigurationBuilder;
import org.mule.runtime.core.api.config.ConfigurationException;
import org.mule.runtime.core.api.config.bootstrap.ArtifactType;
import org.mule.runtime.core.api.config.builders.AbstractResourceConfigurationBuilder;
import org.mule.runtime.core.api.lifecycle.LifecycleManager;
import org.mule.runtime.core.internal.config.ParentMuleContextAwareConfigurationBuilder;
import org.mule.runtime.core.internal.context.DefaultMuleContext;
import org.mule.runtime.core.internal.context.MuleContextWithRegistries;
import org.mule.runtime.deployment.model.api.artifact.ArtifactContext;

import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * <code>SpringXmlConfigurationBuilder</code> enables Mule to be configured from a Spring XML Configuration file used with Mule
 * name-spaces. Multiple configuration files can be loaded from this builder (specified as a comma-separated list).
 */
public class SpringXmlConfigurationBuilder extends AbstractResourceConfigurationBuilder
    implements ParentMuleContextAwareConfigurationBuilder {

  private ArtifactDeclaration artifactDeclaration = new ArtifactDeclaration();
  private boolean enableLazyInit = false;

  private SpringRegistry registry;

  private ApplicationContext parentContext;
  private MuleArtifactContext muleArtifactContext;
  private ArtifactType artifactType;

  public SpringXmlConfigurationBuilder(ArtifactAst artifactAst, Map<String, String> artifactProperties,
                                       ArtifactType artifactType, boolean enableLazyInit)
      throws ConfigurationException {
    super(artifactAst, artifactProperties);
    this.artifactType = artifactType;
    this.enableLazyInit = enableLazyInit;
  }

  public SpringXmlConfigurationBuilder(ArtifactAst artifactAst, Map<String, String> artifactProperties, ArtifactType artifactType)
      throws ConfigurationException {
    this(artifactAst, artifactProperties, artifactType, false);
  }

  public SpringXmlConfigurationBuilder(ArtifactAst artifactAst) throws ConfigurationException {
    this(artifactAst, emptyMap(), APP);
  }

  public SpringXmlConfigurationBuilder(ArtifactAst artifactAst, Map<String, String> artifactProperties)
      throws ConfigurationException {
    this(artifactAst, artifactProperties, APP, false);
  }

  public SpringXmlConfigurationBuilder(ArtifactAst artifactAst, boolean enableLazyInit)
      throws ConfigurationException {
    super(artifactAst, emptyMap());
    this.artifactType = APP;
    this.enableLazyInit = enableLazyInit;
  }

  public SpringXmlConfigurationBuilder(ArtifactAst artifactAst, ArtifactDeclaration artifactDeclaration,
                                       Map<String, String> artifactProperties, ArtifactType artifactType,
                                       boolean enableLazyInitialisation)
      throws ConfigurationException {
    this(artifactAst, artifactProperties, artifactType, enableLazyInitialisation);
    this.artifactDeclaration = artifactDeclaration;
  }

  public static ConfigurationBuilder createConfigurationBuilder(ArtifactAst artifactAst, MuleContext domainContext,
                                                                boolean enableLazyInitialisation)
      throws ConfigurationException {
    final SpringXmlConfigurationBuilder springXmlConfigurationBuilder =
        new SpringXmlConfigurationBuilder(artifactAst, emptyMap(), APP, enableLazyInitialisation);
    if (domainContext != null) {
      springXmlConfigurationBuilder.setParentContext(domainContext);
    }
    return springXmlConfigurationBuilder;
  }

  @Override
  protected void doConfigure(MuleContext muleContext) throws Exception {
    muleArtifactContext = createApplicationContext(muleContext);
    createSpringRegistry(muleContext, muleArtifactContext);
  }

  /**
   * Template method for modifying the list of resources to be loaded. This operation is a no-op by default.
   *
   * @param allResources the list of {@link ConfigResource} to be loaded
   */
  @SuppressWarnings("unused")
  protected void addResources(List<ConfigResource> allResources) {}

  private MuleArtifactContext createApplicationContext(MuleContext muleContext) throws Exception {
    OptionalObjectsController applicationObjectcontroller = new DefaultOptionalObjectsController();
    OptionalObjectsController parentObjectController = null;

    if (parentContext instanceof MuleArtifactContext) {
      parentObjectController = ((MuleArtifactContext) parentContext).getOptionalObjectsController();
    }

    if (parentObjectController != null) {
      applicationObjectcontroller = new CompositeOptionalObjectsController(applicationObjectcontroller, parentObjectController);
    }

    // TODO MULE-10084 : Refactor to only accept artifactConfiguration and not artifactConfigResources
    final MuleArtifactContext muleArtifactContext =
        doCreateApplicationContext(muleContext, artifactDeclaration, applicationObjectcontroller, artifactAst);
    serviceConfigurators.forEach(serviceConfigurator -> serviceConfigurator.configure(muleContext.getCustomizationService()));
    return muleArtifactContext;
  }

  private MuleArtifactContext doCreateApplicationContext(MuleContext muleContext,
                                                         ArtifactDeclaration artifactDeclaration,
                                                         OptionalObjectsController optionalObjectsController,
                                                         ArtifactAst artifactAst) {
    if (enableLazyInit) {
      return new LazyMuleArtifactContext(muleContext, artifactDeclaration,
                                         artifactAst,
                                         optionalObjectsController,
                                         getArtifactProperties(), artifactType,
                                         resolveContextArtifactPluginClassLoaders(),
                                         resolveComponentModelInitializer(),
                                         resolveParentConfigurationProperties());
    }

    return new MuleArtifactContext(muleContext, artifactDeclaration, artifactAst,
                                   optionalObjectsController,
                                   getArtifactProperties(), artifactType, resolveContextArtifactPluginClassLoaders(),
                                   resolveParentConfigurationProperties());
  }

  private Optional<ConfigurationProperties> resolveParentConfigurationProperties() {
    Optional<ConfigurationProperties> parentConfigurationProperties = empty();
    if (parentContext != null) {
      parentConfigurationProperties = of(parentContext.getBean(ConfigurationProperties.class));
    }

    return parentConfigurationProperties;
  }

  private Optional<ComponentModelInitializer> resolveComponentModelInitializer() {
    Optional<ComponentModelInitializer> parentLazyComponentInitializer = empty();
    if (parentContext != null && parentContext instanceof ComponentModelInitializer) {
      parentLazyComponentInitializer = of((ComponentModelInitializer) parentContext);
    }

    return parentLazyComponentInitializer;
  }

  private void createSpringRegistry(MuleContext muleContext, ApplicationContext applicationContext) throws Exception {
    if (parentContext != null) {
      createRegistryWithParentContext(muleContext, applicationContext, parentContext);
    } else {
      registry = new SpringRegistry(applicationContext, muleContext, muleArtifactContext.getDependencyResolver(),
                                    ((DefaultMuleContext) muleContext).getLifecycleInterceptor());
    }

    // Note: The SpringRegistry must be created before
    // muleArtifactContext.refresh() gets called because
    // some beans may try to look up other beans via the Registry during
    // preInstantiateSingletons().
    ((MuleContextWithRegistries) muleContext).addRegistry(registry);
  }

  private void createRegistryWithParentContext(MuleContext muleContext, ApplicationContext applicationContext,
                                               ApplicationContext parentContext)
      throws ConfigurationException {
    if (applicationContext instanceof ConfigurableApplicationContext) {
      ((ConfigurableApplicationContext) applicationContext).setParent(parentContext);
      registry = new SpringRegistry(applicationContext, muleContext, muleArtifactContext.getDependencyResolver(),
                                    ((DefaultMuleContext) muleContext).getLifecycleInterceptor());
    } else {
      throw new ConfigurationException(I18nMessageFactory
          .createStaticMessage("Cannot set a parent context if the ApplicationContext does not implement ConfigurableApplicationContext"));
    }
  }

  @Override
  protected void applyLifecycle(LifecycleManager lifecycleManager) throws Exception {
    // If the MuleContext is started, start all objects in the new Registry.
    if (lifecycleManager.isPhaseComplete(Startable.PHASE_NAME)) {
      lifecycleManager.fireLifecycle(Startable.PHASE_NAME);
    }
  }

  public ArtifactContext createArtifactContext() {
    return new SpringArtifactContext(muleArtifactContext);
  }

  @Override
  public void setParentContext(MuleContext domainContext) {
    this.parentContext = ((MuleContextWithRegistries) domainContext).getRegistry().get("springApplicationContext");
  }
}
