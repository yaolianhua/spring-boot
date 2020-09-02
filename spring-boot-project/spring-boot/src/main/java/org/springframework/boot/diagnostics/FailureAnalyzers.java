/*
 * Copyright 2012-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot.diagnostics;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.SpringBootExceptionReporter;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.core.log.LogMessage;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

/**
 * Utility to trigger {@link FailureAnalyzer} and {@link FailureAnalysisReporter}
 * instances loaded from {@code spring.factories}.
 * <p>
 * A {@code FailureAnalyzer} that requires access to the {@link BeanFactory} in order to
 * perform its analysis can implement {@code BeanFactoryAware} to have the
 * {@code BeanFactory} injected prior to {@link FailureAnalyzer#analyze(Throwable)} being
 * called.
 *
 * @author Andy Wilkinson
 * @author Phillip Webb
 * @author Stephane Nicoll
 */
final class FailureAnalyzers implements SpringBootExceptionReporter {

	private static final Log logger = LogFactory.getLog(FailureAnalyzers.class);

	private final ClassLoader classLoader;

	private final List<FailureAnalyzer> analyzers;

	FailureAnalyzers(ConfigurableApplicationContext context) {
		this(context, null);
	}

	FailureAnalyzers(ConfigurableApplicationContext context, ClassLoader classLoader) {
		Assert.notNull(context, "Context must not be null");
		this.classLoader = (classLoader != null) ? classLoader : context.getClassLoader();
		/**
		 * @see org.springframework.boot.autoconfigure.diagnostics.analyzer.NoSuchBeanDefinitionFailureAnalyzer
		 * @see org.springframework.boot.autoconfigure.flyway.FlywayMigrationScriptMissingFailureAnalyzer
		 * @see org.springframework.boot.autoconfigure.jdbc.DataSourceBeanCreationFailureAnalyzer
		 * @see org.springframework.boot.autoconfigure.jdbc.HikariDriverConfigurationFailureAnalyzer
		 * @see org.springframework.boot.autoconfigure.r2dbc.ConnectionFactoryBeanCreationFailureAnalyzer
		 * @see org.springframework.boot.autoconfigure.session.NonUniqueSessionRepositoryFailureAnalyzer
		 *
		 * @see org.springframework.boot.diagnostics.analyzer.BeanCurrentlyInCreationFailureAnalyzer
		 * @see org.springframework.boot.diagnostics.analyzer.BeanDefinitionOverrideFailureAnalyzer
		 * @see org.springframework.boot.diagnostics.analyzer.BeanNotOfRequiredTypeFailureAnalyzer
		 * @see org.springframework.boot.diagnostics.analyzer.BindFailureAnalyzer
		 * @see org.springframework.boot.diagnostics.analyzer.BindValidationFailureAnalyzer
		 * @see org.springframework.boot.diagnostics.analyzer.UnboundConfigurationPropertyFailureAnalyzer
		 * @see org.springframework.boot.diagnostics.analyzer.ConnectorStartFailureAnalyzer
		 * @see org.springframework.boot.diagnostics.analyzer.NoSuchMethodFailureAnalyzer
		 * @see org.springframework.boot.diagnostics.analyzer.NoUniqueBeanDefinitionFailureAnalyzer
		 * @see org.springframework.boot.diagnostics.analyzer.PortInUseFailureAnalyzer
		 * @see org.springframework.boot.diagnostics.analyzer.ValidationExceptionFailureAnalyzer
		 * @see org.springframework.boot.diagnostics.analyzer.InvalidConfigurationPropertyNameFailureAnalyzer
		 * @see org.springframework.boot.diagnostics.analyzer.InvalidConfigurationPropertyValueFailureAnalyzer
		 */
		this.analyzers = loadFailureAnalyzers(this.classLoader);
		/**
		 * 给实现了{@link BeanFactoryAware}接口的{@link FailureAnalyzer}设置bean工厂{@link org.springframework.beans.factory.support.DefaultListableBeanFactory}
		 * @see org.springframework.boot.autoconfigure.diagnostics.analyzer.NoSuchBeanDefinitionFailureAnalyzer
		 * @see org.springframework.boot.diagnostics.analyzer.NoUniqueBeanDefinitionFailureAnalyzer
		 *
		 * 给实现了{@link EnvironmentAware}接口的{@link FailureAnalyzer}设置应用环境{@link org.springframework.core.env.ConfigurableEnvironment}
		 * 当前应用环境是新创建的{@link org.springframework.core.env.StandardEnvironment}
		 * @see ClassPathBeanDefinitionScanner#getOrCreateEnvironment(BeanDefinitionRegistry)
		 * @see ClassPathBeanDefinitionScanner#getEnvironment()
		 *
		 * @see org.springframework.boot.autoconfigure.jdbc.DataSourceBeanCreationFailureAnalyzer
		 * @see org.springframework.boot.autoconfigure.r2dbc.ConnectionFactoryBeanCreationFailureAnalyzer
		 * @see org.springframework.boot.diagnostics.analyzer.InvalidConfigurationPropertyValueFailureAnalyzer
		 */
		prepareFailureAnalyzers(this.analyzers, context);
	}

	private List<FailureAnalyzer> loadFailureAnalyzers(ClassLoader classLoader) {
		List<String> analyzerNames = SpringFactoriesLoader.loadFactoryNames(FailureAnalyzer.class, classLoader);
		List<FailureAnalyzer> analyzers = new ArrayList<>();
		for (String analyzerName : analyzerNames) {
			try {
				Constructor<?> constructor = ClassUtils.forName(analyzerName, classLoader).getDeclaredConstructor();
				ReflectionUtils.makeAccessible(constructor);
				analyzers.add((FailureAnalyzer) constructor.newInstance());
			}
			catch (Throwable ex) {
				logger.trace(LogMessage.format("Failed to load %s", analyzerName), ex);
			}
		}
		AnnotationAwareOrderComparator.sort(analyzers);
		return analyzers;
	}

	private void prepareFailureAnalyzers(List<FailureAnalyzer> analyzers, ConfigurableApplicationContext context) {
		for (FailureAnalyzer analyzer : analyzers) {
			prepareAnalyzer(context, analyzer);
		}
	}

	private void prepareAnalyzer(ConfigurableApplicationContext context, FailureAnalyzer analyzer) {
		if (analyzer instanceof BeanFactoryAware) {
			((BeanFactoryAware) analyzer).setBeanFactory(context.getBeanFactory());
		}
		if (analyzer instanceof EnvironmentAware) {
			((EnvironmentAware) analyzer).setEnvironment(context.getEnvironment());
		}
	}

	@Override
	public boolean reportException(Throwable failure) {
		FailureAnalysis analysis = analyze(failure, this.analyzers);
		return report(analysis, this.classLoader);
	}

	private FailureAnalysis analyze(Throwable failure, List<FailureAnalyzer> analyzers) {
		for (FailureAnalyzer analyzer : analyzers) {
			try {
				FailureAnalysis analysis = analyzer.analyze(failure);
				if (analysis != null) {
					return analysis;
				}
			}
			catch (Throwable ex) {
				logger.debug(LogMessage.format("FailureAnalyzer %s failed", analyzer), ex);
			}
		}
		return null;
	}

	private boolean report(FailureAnalysis analysis, ClassLoader classLoader) {
		List<FailureAnalysisReporter> reporters = SpringFactoriesLoader.loadFactories(FailureAnalysisReporter.class,
				classLoader);
		if (analysis == null || reporters.isEmpty()) {
			return false;
		}
		for (FailureAnalysisReporter reporter : reporters) {
			reporter.report(analysis);
		}
		return true;
	}

}
