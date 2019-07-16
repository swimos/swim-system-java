// Copyright 2015-2019 SWIM.AI inc.
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
package swim.uri;

import swim.structure.Form;
import swim.structure.Item;
import swim.structure.Record;
import swim.structure.Text;
import swim.structure.Value;

public class UriPatternForm extends Form<UriPattern> {
  final UriPattern unit;

  UriPatternForm(UriPattern unit) {
    this.unit = unit;
  }

  @Override
  public Class<UriPattern> type() {
    return UriPattern.class;
  }

  @Override
  public UriPattern unit() {
    return unit;
  }

  @Override
  public Item mold(UriPattern value) {
    return value != null ? Text.from(value.toString()) : Value.extant();
  }

  @Override
  public UriPattern cast(Item value) {
    if (value instanceof Record) {
      value = ((Record) value).target();
    }
    try {
      final String string = value.stringValue();
      if (string != null) {
        return UriPattern.parse(string);
      }
    } catch (UriException e) { }
    return null;
  }
}