/*
 * Copyright 2019 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.template.soy.invocationbuilders.passes;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.collect.ImmutableList;
import com.google.template.soy.invocationbuilders.javatypes.JavaNumberSubtype;
import com.google.template.soy.invocationbuilders.javatypes.JavaType;
import com.google.template.soy.invocationbuilders.javatypes.ListJavaType;
import com.google.template.soy.invocationbuilders.javatypes.MapJavaType;
import com.google.template.soy.invocationbuilders.javatypes.ProtoEnumJavaType;
import com.google.template.soy.invocationbuilders.javatypes.ProtoJavaType;
import com.google.template.soy.invocationbuilders.javatypes.SimpleJavaType;
import com.google.template.soy.types.AbstractMapType;
import com.google.template.soy.types.ListType;
import com.google.template.soy.types.NullType;
import com.google.template.soy.types.SoyProtoEnumType;
import com.google.template.soy.types.SoyProtoType;
import com.google.template.soy.types.SoyType;
import com.google.template.soy.types.UnionType;
import java.util.List;

/** Utils for handling types used in Soy Java invocation builders. */
final class InvocationBuilderTypeUtils {

  private InvocationBuilderTypeUtils() {}

  /**
   * Gets Java type from Soy type.
   *
   * <p>NOTE: TODO(b/140064271): Add handling for composite types. Update this method's javadoc when
   * this returns a list of java types (for handling unions).
   */
  static final ImmutableList<JavaType> getJavaTypes(SoyType soyType, boolean shouldMakeNullable) {
    boolean nonLegacyMap = true;
    ImmutableList<JavaType> types = ImmutableList.of();
    switch (soyType.getKind()) {
      case BOOL:
        types = ImmutableList.of(SimpleJavaType.BOOLEAN);
        break;
      case INT:
        types = ImmutableList.of(JavaNumberSubtype.LONG);

        break;
      case FLOAT:
        types = ImmutableList.of(JavaNumberSubtype.DOUBLE);
        break;
      case STRING:
        types = ImmutableList.of(SimpleJavaType.STRING);
        break;
      case HTML:
        types = ImmutableList.of(SimpleJavaType.HTML);
        break;
      case JS:
        types = ImmutableList.of(SimpleJavaType.JS);
        break;
      case URI:
        types = ImmutableList.of(SimpleJavaType.URL);
        break;
      case TRUSTED_RESOURCE_URI:
        types = ImmutableList.of(SimpleJavaType.TRUSTED_RESOURCE_URL);
        break;
      case PROTO:
        SoyProtoType asProto = (SoyProtoType) soyType;
        types = ImmutableList.of(new ProtoJavaType(asProto.getDescriptor()));
        break;
      case PROTO_ENUM:
        SoyProtoEnumType asProtoEnum = (SoyProtoEnumType) soyType;
        types = ImmutableList.of(new ProtoEnumJavaType(asProtoEnum.getDescriptor()));
        break;
      case LIST:
        List<JavaType> listElementTypes =
            getJavaTypes(((ListType) soyType).getElementType(), /* shouldMakeNullable= */ false);
        if (listElementTypes.size() == 1 && listElementTypes.get(0).isGenericsTypeSupported()) {
          return ImmutableList.of(new ListJavaType(listElementTypes.get(0)));
        } // Currently, we don't handle multiple element types b/c of type erasure.
        types = ImmutableList.of();
        break;
      case LEGACY_OBJECT_MAP:
        nonLegacyMap = false; // fall through
      case MAP:
        AbstractMapType soyAbstractMapType = (AbstractMapType) soyType;
        List<JavaType> keyTypes =
            getJavaTypes(soyAbstractMapType.getKeyType(), /* shouldMakeNullable= */ false);
        if (keyTypes.size() != 1 || !keyTypes.get(0).isGenericsTypeSupported()) {
          break;
        }
        List<JavaType> valueTypes =
            getJavaTypes(soyAbstractMapType.getValueType(), /* shouldMakeNullable= */ false);
        if (valueTypes.size() != 1 || !valueTypes.get(0).isGenericsTypeSupported()) {
          break;
        }
        types = ImmutableList.of(new MapJavaType(keyTypes.get(0), valueTypes.get(0), nonLegacyMap));
        break;
      case UNION:
        types = convertSoyUnionTypeToJavaTypes((UnionType) soyType);
        break;
      case ANY:
      case UNKNOWN:
        types = ImmutableList.of(SimpleJavaType.OBJECT);
        break;
      case ATTRIBUTES:
        types = ImmutableList.of(SimpleJavaType.ATTRIBUTES);
        break;
      case CSS:
        types = ImmutableList.of(SimpleJavaType.CSS);
        break;
      case RECORD:
      case ERROR:
      case NULL:
      case VE:
      case VE_DATA:
        break;
    }

    if (shouldMakeNullable) {
      return types.stream().map(type -> type.asNullable()).collect(toImmutableList());
    }
    return types;
  }

  /**
   * Returns whether {@code type} is unsettable from Java. Params of this type should not count
   * against whether a template is fully handled by this generated API.
   */
  public static boolean isJavaIncompatible(SoyType type) {
    switch (type.getKind()) {
      case VE:
      case VE_DATA:
        return true;
      default:
        return false;
    }
  }

  /**
   * Converts a soy {@link UnionType} to a list of {@link JavaTypes} corresponding to the union
   * members.
   *
   * <p>Caveat #1: If we don't yet support one of the union member types, this returns an empty list
   * so we can skip over the param until it can be fully handled.
   *
   * <p>Caveat #2: If the types in the union would lead to type erasure problems (e.g. setFoo({@code
   * List<Long>} val) and setFoo({@code List<String>} val)), then we return an empty list and skip
   * over the entire union for now.
   */
  private static ImmutableList<JavaType> convertSoyUnionTypeToJavaTypes(UnionType unionType) {
    // Figure out if the union contains the {@link NullType}, which tells us if the param setters
    // should be nullable.
    boolean unionAllowsNull =
        unionType.getMembers().stream().anyMatch(member -> member instanceof NullType);

    // Collect a list of the Java types for each of the union member types.
    ImmutableList.Builder<JavaType> javaTypeListBuilder = new ImmutableList.Builder<>();
    for (SoyType soyUnionMemberType : unionType.getMembers()) {
      if (soyUnionMemberType instanceof NullType) {
        continue;
      }
      List<JavaType> javaTypesForUnionMember =
          getJavaTypes(soyUnionMemberType, /* shouldMakeNullable= */ unionAllowsNull);

      // If we don't know how to handle one of the member types, skip over the entire union.
      if (javaTypesForUnionMember.isEmpty()) {
        return ImmutableList.of();
      }

      javaTypeListBuilder.addAll(javaTypesForUnionMember);
    }

    // If the param would cause type erasure problems, skip over it.
    return clearListIfHasTypeErasureOverloadCollisions(javaTypeListBuilder.build());
  }

  /**
   * If given list of types would lead to type erasure problems (when we generate a setFoo method
   * for each type), then return an empty list. Otherwise return the {@code types} list as-is. (e.g.
   *
   * <p>For example, {@code setFoo(List<String> strings)} and {@code setFoo(List<Number> numbers)}
   * would cause a collision.
   */
  private static ImmutableList<JavaType> clearListIfHasTypeErasureOverloadCollisions(
      ImmutableList<JavaType> types) {

    long numTopLevelListTypes = types.stream().filter(type -> type instanceof ListJavaType).count();

    long numTopLevelMapTypes = types.stream().filter(type -> type instanceof MapJavaType).count();

    if (numTopLevelListTypes > 1 || numTopLevelMapTypes > 1) {
      return ImmutableList.of();
    }
    return types;
  }
}