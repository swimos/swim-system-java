// Copyright 2015-2021 Swim inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package swim.mqtt;

public abstract class MqttEntity<T> extends MqttPart {

  private static MqttEntity<Object> empty;

  @SuppressWarnings("unchecked")
  public static <T> MqttEntity<T> empty() {
    if (empty == null) {
      empty = new MqttEmpty();
    }
    return (MqttEntity<T>) empty;
  }

  public abstract boolean isDefined();

  public abstract T get();

  public abstract int mqttSize();

}
