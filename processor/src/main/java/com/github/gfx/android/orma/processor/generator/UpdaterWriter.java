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
package com.github.gfx.android.orma.processor.generator;

import com.github.gfx.android.orma.processor.util.Annotations;
import com.github.gfx.android.orma.processor.model.AssociationDefinition;
import com.github.gfx.android.orma.processor.model.ColumnDefinition;
import com.github.gfx.android.orma.processor.ProcessingContext;
import com.github.gfx.android.orma.processor.exception.ProcessingException;
import com.github.gfx.android.orma.processor.model.SchemaDefinition;
import com.github.gfx.android.orma.processor.util.Types;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.TypeSpec;

import java.util.ArrayList;
import java.util.List;

import javax.lang.model.element.Modifier;

public class UpdaterWriter extends BaseWriter {

    private final SchemaDefinition schema;

    private final ConditionQueryHelpers conditionQueryHelpers;

    public UpdaterWriter(ProcessingContext context, SchemaDefinition schema) {
        super(context);
        this.schema = schema;
        conditionQueryHelpers = new ConditionQueryHelpers(context, schema, schema.getUpdaterClassName());
    }

    @Override
    public TypeSpec buildTypeSpec() {
        TypeSpec.Builder classBuilder = TypeSpec.classBuilder(schema.getUpdaterClassName().simpleName());
        classBuilder.addModifiers(Modifier.PUBLIC);
        classBuilder.superclass(Types.getUpdater(schema.getModelClassName(), schema.getUpdaterClassName()));

        classBuilder.addMethods(buildMethodSpecs());

        return classBuilder.build();
    }

    public List<MethodSpec> buildMethodSpecs() {
        List<MethodSpec> methodSpecs = new ArrayList<>();

        methodSpecs.add(
                MethodSpec.constructorBuilder()
                        .addModifiers(Modifier.PUBLIC)
                        .addParameter(Types.OrmaConnection, "conn")
                        .addParameter(Types.getSchema(schema.getModelClassName()), "schema")
                        .addStatement("super(conn, schema)")
                        .build()
        );

        methodSpecs.add(
                MethodSpec.constructorBuilder()
                        .addModifiers(Modifier.PUBLIC)
                        .addParameter(schema.getRelationClassName(), "relation")
                        .addStatement("super(relation)")
                        .build()
        );

        schema.getColumnsWithoutAutoId().forEach(column -> {
            AssociationDefinition r = column.getAssociation();

            if (r == null) {
                String paramName = column.name;
                methodSpecs.add(
                        MethodSpec.methodBuilder(column.name)
                                .addModifiers(Modifier.PUBLIC)
                                .returns(schema.getUpdaterClassName())
                                .addParameter(
                                        ParameterSpec.builder(column.getType(), paramName)
                                                .addAnnotations(column.nullabilityAnnotations())
                                                .build()
                                )
                                .addStatement("contents.put($S, $L)", column.getEscapedColumnName(false),
                                        column.buildSerializeExpr("conn", paramName))
                                .addStatement("return this")
                                .build()
                );

            } else {
                if (r.isSingleAssociation()) {
                    methodSpecs.add(
                            MethodSpec.methodBuilder(column.name)
                                    .addModifiers(Modifier.PUBLIC)
                                    .returns(schema.getUpdaterClassName())
                                    .addParameter(
                                            ParameterSpec.builder(column.getType(), column.name + "Reference")
                                                    .addAnnotation(Annotations.nonNull())
                                                    .build()
                                    )
                                    .addStatement("contents.put($S, $L.getId())",
                                            column.getEscapedColumnName(false), column.name + "Reference")
                                    .addStatement("return this")
                                    .build()
                    );
                }

                SchemaDefinition modelSchema = context.getSchemaDef(r.getModelType());
                if (modelSchema == null) {
                    // FIXME: just stack errors and return in order to continue processing
                    throw new ProcessingException(Types.SingleAssociation.simpleName() + "<T> can handle only Orma models",
                            column.element
                    );
                }

                ColumnDefinition primaryKey = modelSchema.getPrimaryKey();
                if (primaryKey == null) {
                    throw new ProcessingException("SingleAssociation<T> requires the @PrimaryKey field",
                            modelSchema.getElement());
                }

                methodSpecs.add(
                        MethodSpec.methodBuilder(column.name)
                                .addModifiers(Modifier.PUBLIC)
                                .returns(schema.getUpdaterClassName())
                                .addParameter(
                                        ParameterSpec.builder(r.getModelType(), column.name)
                                                .addAnnotations(column.nullabilityAnnotations())
                                                .build()
                                )
                                .addStatement("contents.put($S, $L)",
                                        column.getEscapedColumnName(false),
                                        modelSchema.getPrimaryKey().buildGetColumnExpr(column.name))
                                .addStatement("return this")
                                .build()
                );
            }
        });

        methodSpecs.addAll(conditionQueryHelpers.buildConditionHelpers());

        return methodSpecs;
    }
}
