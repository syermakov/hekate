/*
 * Copyright 2018 The Hekate Project
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

package io.hekate.spring.boot.codec;

import io.hekate.codec.fst.FstCodecFactory;
import io.hekate.spring.boot.ConditionalOnHekateEnabled;
import io.hekate.spring.boot.HekateConfigurer;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configuration for {@link FstCodecFactory}.
 *
 * <h2>Module dependency</h2>
 * <p>
 * FST integration requires
 * <a href="https://search.maven.org/#search%7Cga%7C1%7Cg%3A%22de.ruedigermoeller%22%20a%3A%22fst%22" target="_blank">
 * 'de.ruedigermoeller:fst'
 * </a>
 * to be on the project's classpath.
 * </p>
 *
 * <h2>Configuration</h2>
 * <p>
 * This auto-configuration can be enabled by setting the {@code 'hekate.codec'} property to {@code 'fst'} in the application's
 * configuration.
 * </p>
 *
 * <p>
 * The following properties can be used to customize the auto-configured {@link FstCodecFactory} instance:
 * </p>
 * <ul>
 * <li>{@link FstCodecFactory#setKnownTypes(List) 'hekate.codec.fst.known-types'}</li>
 * <li>{@link FstCodecFactory#setUseUnsafe(boolean) 'hekate.codec.fst.use-unsafe'}</li>
 * <li>{@link FstCodecFactory#setSharedReferences(Boolean) 'hekate.codec.fst.shared-references'}</li>
 * </ul>
 */
@Configuration
@ConditionalOnHekateEnabled
@AutoConfigureBefore(HekateConfigurer.class)
@ConditionalOnProperty(name = "hekate.codec", havingValue = "fst")
public class HekateFstCodecConfigurer {
    /**
     * Constructs a new instance of {@link FstCodecFactory}.
     *
     * @return Codec factory.
     */
    @Bean
    @Qualifier("default")
    @ConfigurationProperties(prefix = "hekate.codec.fst")
    public FstCodecFactory<Object> fstCodecFactory() {
        return new FstCodecFactory<>();
    }
}
