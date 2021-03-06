/*
 * Copyright (c) 2015 FUJI Goro (gfx).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.gfx.android.orma.processor.model;

import com.github.gfx.android.orma.annotation.Column;
import com.github.gfx.android.orma.annotation.OnConflict;
import com.github.gfx.android.orma.annotation.PrimaryKey;
import com.github.gfx.android.orma.processor.ProcessingContext;
import com.github.gfx.android.orma.processor.exception.ProcessingException;
import com.github.gfx.android.orma.processor.util.Annotations;
import com.github.gfx.android.orma.processor.util.SqlTypes;
import com.github.gfx.android.orma.processor.util.Strings;
import com.github.gfx.android.orma.processor.util.Types;
import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;

import android.support.annotation.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Name;
import javax.lang.model.element.VariableElement;

public class ColumnDefinition {

    public static final String kDefaultPrimaryKeyName = "_rowid_";

    public final ProcessingContext context;

    public final SchemaDefinition schema;

    public final VariableElement element;

    public final String name;

    public final String columnName;

    public final TypeName type;

    public final boolean nullable;

    public final boolean primaryKey;

    public final int primaryKeyOnConflict;

    public final boolean autoincrement;

    public final boolean autoId;

    public final boolean indexed;

    public final boolean unique;

    public final int uniqueOnConflict;

    public final String defaultExpr;

    public final Column.Collate collate;

    public final String storageType;

    public final TypeAdapterDefinition typeAdapter;

    public ExecutableElement getter;

    public ExecutableElement setter;

    public ColumnDefinition(SchemaDefinition schema, VariableElement element) {
        this.schema = schema;
        this.element = element;
        context = schema.context;

        // See https://www.sqlite.org/lang_createtable.html for full specification
        Column column = element.getAnnotation(Column.class);
        PrimaryKey primaryKeyAnnotation = element.getAnnotation(PrimaryKey.class);

        name = element.getSimpleName().toString();
        columnName = columnName(column, element);

        type = ClassName.get(element.asType());
        typeAdapter = schema.context.typeAdapterMap.get(type);
        storageType = storageType(context, element, column, type, typeAdapter);

        if (column != null) {
            indexed = column.indexed();
            uniqueOnConflict = column.uniqueOnConflict();
            unique = uniqueOnConflict != OnConflict.NONE || column.unique();
            collate = column.collate();
            defaultExpr = column.defaultExpr();
        } else {
            indexed = false;
            uniqueOnConflict = OnConflict.NONE;
            unique = false;
            defaultExpr = null;
            collate = Column.Collate.BINARY;
        }

        if (primaryKeyAnnotation != null) {
            primaryKeyOnConflict = primaryKeyAnnotation.onConflict();
            primaryKey = true;
            autoincrement = primaryKeyAnnotation.autoincrement();
            autoId = primaryKeyAnnotation.auto() && Types.looksLikeIntegerType(type);
        } else {
            primaryKeyOnConflict = OnConflict.NONE;
            primaryKey = false;
            autoincrement = false;
            autoId = false;
        }

        nullable = hasNullableAnnotation(element);
    }

    // to create primary key columns
    private ColumnDefinition(SchemaDefinition schema) {
        this.schema = schema;
        context = schema.context;
        element = null;
        name = kDefaultPrimaryKeyName;
        columnName = kDefaultPrimaryKeyName;
        type = TypeName.LONG;
        nullable = false;
        primaryKey = true;
        primaryKeyOnConflict = OnConflict.NONE;
        autoincrement = false;
        autoId = true;
        indexed = false;
        unique = false;
        uniqueOnConflict = OnConflict.NONE;
        defaultExpr = "";
        collate = Column.Collate.BINARY;
        typeAdapter = schema.context.typeAdapterMap.get(type);
        storageType = storageType(context, null, null, type, typeAdapter);
    }

    public static ColumnDefinition createDefaultPrimaryKey(SchemaDefinition schema) {
        return new ColumnDefinition(schema);
    }

    public static String getColumnName(Element element) {
        Column column = element.getAnnotation(Column.class);
        return columnName(column, element);
    }

    static String columnName(Column column, Element element) {
        if (column != null && !column.value().equals("")) {
            return column.value();
        } else {
            for (AnnotationMirror annotation : element.getAnnotationMirrors()) {
                Name annotationName = annotation.getAnnotationType().asElement().getSimpleName();
                if (annotationName.contentEquals("SerializedName") // GSON
                        || annotationName.contentEquals("JsonProperty") // Jackson
                        ) {
                    for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry : annotation
                            .getElementValues().entrySet()) {
                        if (entry.getKey().getSimpleName().contentEquals("value")) {
                            return entry.getValue().getValue().toString();
                        }
                    }
                }
            }
        }
        return element.getSimpleName().toString();
    }

    static String storageType(ProcessingContext context, Element element, Column column, TypeName type,
            TypeAdapterDefinition typeAdapter) {
        if (column != null && !Strings.isEmpty(column.storageType())) {
            return column.storageType();
        } else {
            if (typeAdapter != null) {
                return SqlTypes.getSqliteType(typeAdapter.serializedType);
            } else if (Types.isSingleAssociation(type)) {
                return SqlTypes.getSqliteType(TypeName.LONG);
            } else if (Types.isDirectAssociation(context, type)) {
                ColumnDefinition primaryKey = context.getSchemaDef(type).getPrimaryKey();
                if (primaryKey != null) {
                    return SqlTypes.getSqliteType(primaryKey.getType());
                } else {
                    context.addError("Missing @PrimaryKey", element);
                    return "UNKNOWN";
                }
            } else {
                return SqlTypes.getSqliteType(type);
            }
        }
    }

    static boolean hasNullableAnnotation(Element element) {
        for (AnnotationMirror annotation : element.getAnnotationMirrors()) {
            // allow anything named "Nullable"
            if (annotation.getAnnotationType().asElement().getSimpleName().contentEquals("Nullable")) {
                return true;
            }
        }
        return false;
    }

    public void initGetterAndSetter(ExecutableElement getter, ExecutableElement setter) {
        if (getter != null) {
            this.getter = getter;
        }
        if (setter != null) {
            this.setter = setter;
        }
    }

    public String getEscapedColumnName() {
        return getEscapedColumnName(schema.hasDirectAssociations());
    }

    public String getEscapedColumnName(boolean fqn) {
        StringBuilder sb = new StringBuilder();
        if (fqn) {
            context.sqlg.appendIdentifier(sb, schema.getTableName());
            sb.append('.');
        }
        context.sqlg.appendIdentifier(sb, columnName);
        return sb.toString();
    }

    public TypeName getType() {
        return type;
    }

    public TypeName getBoxType() {
        return type.box();
    }

    public TypeName getUnboxType() {
        return Types.asUnboxType(type);
    }

    public TypeName getSerializedType() {
        if (isDirectAssociation() || isSingleAssociation()) {
            SchemaDefinition associatedSchema = getAssociatedSchema();
            ColumnDefinition primaryKey = associatedSchema.getPrimaryKey();
            if (primaryKey != null) {
                return associatedSchema.getPrimaryKey().getSerializedType();
            } else {
                return Types.ByteArray; // dummy
            }
        } else if (typeAdapter != null) {
            return Types.asUnboxType(typeAdapter.serializedType);
        } else {
            return getUnboxType();
        }
    }

    public TypeName getSerializedBoxType() {
        return getSerializedType().box();
    }

    public String getStorageType() {
        return storageType;
    }

    public boolean isNullableInSQL() {
        return nullable;
    }

    public boolean isNullableInJava() {
        return !type.isPrimitive() && nullable;
    }

    /**
     * @return A representation of {@code ColumnDef<T>}
     */
    public ParameterizedTypeName getColumnDefType() {
        return Types.getColumnDef(schema.getModelClassName(), getBoxType());
    }

    public CodeBlock buildSetColumnExpr(CodeBlock rhsExpr) {
        if (setter != null) {
            return CodeBlock.builder()
                    .add("$L($L)", setter.getSimpleName(), rhsExpr)
                    .build();
        } else {
            return CodeBlock.builder()
                    .add("$L = $L", name, rhsExpr)
                    .build();
        }
    }

    public CodeBlock buildGetColumnExpr(String modelExpr) {
        return buildGetColumnExpr(CodeBlock.builder().add("$L", modelExpr).build());
    }

    public CodeBlock buildGetColumnExpr(CodeBlock modelExpr) {
        return CodeBlock.builder()
                .add("$L.$L", modelExpr, getter != null ? getter.getSimpleName() + "()" : name)
                .build();
    }

    public CodeBlock buildSerializedColumnExpr(String connectionExpr, String modelExpr) {
        CodeBlock getColumnExpr = buildGetColumnExpr(modelExpr);

        if (isSingleAssociation()) {
            return CodeBlock.builder().add("$L.getId()", getColumnExpr).build();
        } else if (isDirectAssociation()) {
            SchemaDefinition associatedSchema = getAssociatedSchema();
            ColumnDefinition primaryKey = associatedSchema.getPrimaryKey();
            if (primaryKey != null) {
                return primaryKey.buildGetColumnExpr(getColumnExpr);
            } else {
                return CodeBlock.builder().add("null /* missing @PrimaryKey */").build();
            }
        } else if (needsTypeAdapter()) {
            return CodeBlock.builder()
                    .add(buildSerializeExpr(connectionExpr, getColumnExpr))
                    .build();
        } else {
            return getColumnExpr;
        }
    }

    public CodeBlock buildSerializeExpr(String connectionExpr, String valueExpr) {
        return buildSerializeExpr(connectionExpr, CodeBlock.builder().add("$L", valueExpr).build());
    }

    public CodeBlock buildSerializeExpr(String connectionExpr, CodeBlock valueExpr) {
        // TODO: parameter injection for static type serializers
        if (needsTypeAdapter()) {
            if (typeAdapter == null) {
                new Throwable().printStackTrace(); // FIXME: remove this
                throw new ProcessingException("Missing @StaticTypeAdapter to serialize " + type, element);
            }

            return CodeBlock.builder()
                    .add("$T.$L($L)", typeAdapter.typeAdapterImpl, typeAdapter.getSerializerName(), valueExpr)
                    .build();
        } else {
            return valueExpr;
        }
    }

    public CodeBlock buildDeserializeExpr(String connectionExpr, CodeBlock valueExpr) {
        // TODO: parameter injection for static type serializers
        if (needsTypeAdapter()) {
            if (typeAdapter == null) {
                throw new ProcessingException("Missing @StaticTypeAdapter to deserialize " + type, element);
            }

            return CodeBlock.builder()
                    .add("$T.$L($L)", typeAdapter.typeAdapterImpl, typeAdapter.getDeserializerName(), valueExpr)
                    .build();
        } else {
            return CodeBlock.builder()
                    .add("$L", valueExpr)
                    .build();
        }
    }

    public boolean needsTypeAdapter() {
        return Types.needsTypeAdapter(getUnboxType());
    }

    public Collection<AnnotationSpec> nullabilityAnnotations() {
        if (type.isPrimitive()) {
            return Collections.emptyList();
        }

        if (nullable) {
            return Collections.singletonList(Annotations.nullable());
        } else {
            return Collections.singletonList(Annotations.nonNull());
        }
    }

    @Nullable
    public AssociationDefinition getAssociation() {
        if (Types.isSingleAssociation(type)) {
            return AssociationDefinition.createSingleAssociation(type);
        } else if (Types.isDirectAssociation(context, type)) {
            return AssociationDefinition.createDirectAssociation(type);
        }
        return null;
    }

    public boolean isDirectAssociation() {
        return Types.isDirectAssociation(context, type);
    }

    public boolean isSingleAssociation() {
        return Types.isSingleAssociation(type);
    }

    public SchemaDefinition getAssociatedSchema() {
        AssociationDefinition r = getAssociation();
        assert r != null;
        return context.getSchemaDef(r.modelType);
    }
}
