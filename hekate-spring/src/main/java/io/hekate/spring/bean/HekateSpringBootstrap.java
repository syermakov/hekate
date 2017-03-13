/*
 * Copyright 2017 The Hekate Project
 *
 * The Hekate Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.hekate.spring.bean;

import io.hekate.core.Hekate;
import io.hekate.core.HekateBootstrap;
import io.hekate.core.service.ServiceFactory;
import io.hekate.inject.InjectionService;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

/**
 * <span class="startHere">&laquo; start here</span>Main entry point to Spring Framework integration.
 *
 * <h2>Overview</h2>
 * <p>
 * All configurable components and service factories of {@link Hekate} can be used as plain Spring &lt;bean&gt;s and can be configured via
 * &lt;property&gt; setters. This class provides an extension of {@link HekateBootstrap} class that makes it possible to easily
 * configure {@link Hekate} nodes and bound them to the lifecycle of Spring context.
 * </p>
 *
 * <h2>Module dependency</h2>
 * <p>
 * Spring Framework integration is provided by the 'hekate-spring' module and can be imported into the project dependency management system
 * as in the example below:
 * </p>
 * <div class="tabs">
 * <ul>
 * <li><a href="#maven">Maven</a></li>
 * <li><a href="#gradle">Gradle</a></li>
 * <li><a href="#ivy">Ivy</a></li>
 * </ul>
 * <div id="maven">
 * <pre>{@code
 * <dependency>
 *   <groupId>io.hekate</groupId>
 *   <artifactId>hekate-spring</artifactId>
 *   <version>REPLACE_VERSION</version>
 * </dependency>
 * }</pre>
 * </div>
 * <div id="gradle">
 * <pre>{@code
 * compile group: 'io.hekate', name: 'hekate-spring', version: 'REPLACE_VERSION'
 * }</pre>
 * </div>
 * <div id="ivy">
 * <pre>{@code
 * <dependency org="io.hekate" name="hekate-spring" rev="REPLACE_VERSION"/>
 * }</pre>
 * </div>
 * </div>
 *
 *
 * <h2>Configuration</h2>
 * <p>
 * {@link Hekate} nodes can be configured by using two approaches:
 * </p>
 * <ol>
 * <li>
 * By using &lt;bean&gt; tag from Spring's default XML namespace and setting all configuration options via &lt;property&gt; tag and
 * nested beans.
 * </li>
 * <li>
 * By using an extension of Spring XML schema with {@link Hekate}-specific XML namespace: <b>http://www.hekate.io/spring/hekate-core</b>
 * </li>
 * </ol>
 *
 * <p>
 * Below is the example of a minimalistic {@link Hekate} node bean with all configuration options set to their default values.
 * </p>
 * <div class="tabs">
 * <ul>
 * <li><a href="#simple-xsd">Spring XSD</a></li>
 * <li><a href="#simple-bean">Spring bean</a></li>
 * </ul>
 * <div id="simple-xsd">
 * ${source:simple-xsd.xml#example}
 * </div>
 * <div id="simple-bean">
 * ${source:simple-bean.xml#example}
 * </div>
 * </div>
 *
 * <p>
 * ...and a more <a href="replace-me-with-js:$('#complete-example').toggle()"><b>complete example</b></a>
 * </p>
 * <div class="tabs" id="complete-example" style="display: none">
 * <ul>
 * <li><a href="#complete-xsd">Spring XSD</a></li>
 * <li><a href="#complete-bean">Spring bean</a></li>
 * </ul>
 * <div id="complete-xsd">
 * ${source:complete-xsd.xml#example}
 * </div>
 * <div id="complete-bean">
 * ${source:complete-bean.xml#example}
 * </div>
 * </div>
 */
public class HekateSpringBootstrap extends HekateBootstrap implements InitializingBean, DisposableBean, FactoryBean<Hekate>,
    ApplicationContextAware {
    private Hekate instance;

    private ApplicationContext ctx;

    private ServiceFactory<InjectionService> injectorFactory;

    @Override
    public void afterPropertiesSet() throws Exception {
        injectorFactory = new ServiceFactory<InjectionService>() {
            @Override
            public InjectionService createService() {
                return new SpringInjectionService(ctx);
            }

            @Override
            public String toString() {
                return SpringInjectionService.class.getSimpleName() + "Factory";
            }
        };

        withService(injectorFactory);

        instance = join();
    }

    @Override
    public void destroy() throws Exception {
        if (instance != null) {
            try {
                instance.leave();
            } finally {
                if (injectorFactory != null && getServices() != null) {
                    getServices().remove(injectorFactory);
                }

                injectorFactory = null;
                instance = null;
            }
        }
    }

    @Override
    public Hekate getObject() throws Exception {
        return instance;
    }

    @Override
    public Class<?> getObjectType() {
        return Hekate.class;
    }

    @Override
    public boolean isSingleton() {
        return true;
    }

    @Override
    public void setApplicationContext(ApplicationContext ctx) throws BeansException {
        this.ctx = ctx;
    }
}