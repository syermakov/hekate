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

package foo.bar;

import io.hekate.core.PropertyProvider;
import java.util.Collections;
import java.util.Map;

public class SomePropertyProvider implements PropertyProvider {
    @Override
    public Map<String, String> getProperties() {
        return Collections.singletonMap("some-property-from-provider", "some-value-from-provider");
    }
}
