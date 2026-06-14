package com.djpa.processor;

import com.djpa.annotations.FieldProperty;
import com.djpa.annotations.GenerateFields;
import com.google.auto.service.AutoService;
import com.squareup.javapoet.*;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;

import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.util.*;

@SupportedAnnotationTypes("com.djpa.annotations.GenerateFields")
@SupportedSourceVersion(SourceVersion.RELEASE_17)
@AutoService(Processor.class)
public class FieldsProcessor extends AbstractProcessor {

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {

        for (Element element : roundEnv.getElementsAnnotatedWith(GenerateFields.class)) {

            if (!(element instanceof TypeElement typeElement)) continue;

            try {
                generate(typeElement);
            } catch (Exception e) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, e.getMessage());
            }
        }

        return true;
    }

    private void generate(TypeElement typeElement) throws IOException {

        String packageName = processingEnv.getElementUtils()
                .getPackageOf(typeElement).toString();

        String originalName = typeElement.getSimpleName().toString();
        String generatedName = originalName + "Fields";

        boolean isRecord = typeElement.getKind() == ElementKind.RECORD;

        TypeSpec.Builder clazz = TypeSpec.classBuilder(generatedName)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL);

        clazz.addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PRIVATE)
                .build());

        List<FieldInfo> fields = extractFields(typeElement, isRecord);

        List<String> fieldPropertyNames = new ArrayList<>();

        // =========================
        // FIELD PROPERTIES
        // =========================
        for (FieldInfo f : fields) {

            String varName = f.variableName; // lowercase field name

            FieldSpec fieldSpec = FieldSpec.builder(
                            ParameterizedTypeName.get(
                                    ClassName.get(FieldProperty.class),
                                    ClassName.get(f.type),
                                    ClassName.get(f.elementType)
                            ),
                            varName,
                            Modifier.PUBLIC,
                            Modifier.STATIC,
                            Modifier.FINAL
                    )
                    .initializer("new $T($S, $T.class, $L)",
                            FieldProperty.class,
                            f.name,
                            ClassName.get(f.type),
                            f.elementType == null ? "null" : f.elementType + ".class"
                    )
                    .build();

            clazz.addField(fieldSpec);
            fieldPropertyNames.add(varName);
        }

        // =========================
        // ALL_FIELDS
        // =========================
        CodeBlock allFields = CodeBlock.builder()
                .add("List.of(")
                .add(String.join(", ", fieldPropertyNames))
                .add(")")
                .build();

        clazz.addField(FieldSpec.builder(
                        ParameterizedTypeName.get(
                                ClassName.get(List.class),
                                WildcardTypeName.subtypeOf(Object.class)
                        ),
                        "ALL_FIELDS",
                        Modifier.PUBLIC,
                        Modifier.STATIC,
                        Modifier.FINAL
                )
                .initializer(allFields)
                .build());

        // =========================
        // getType()
        // =========================
        MethodSpec.Builder getType = MethodSpec.methodBuilder("getType")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(ParameterizedTypeName.get(ClassName.get(Class.class), WildcardTypeName.subtypeOf(Object.class)))
                .addParameter(String.class, "name");

        getType.beginControlFlow("switch(name)");

        for (FieldInfo f : fields) {
            getType.addStatement("case $S -> { return $T.class; }",
                    f.name, ClassName.get(f.type));
        }

        getType.addStatement("default -> throw new IllegalArgumentException($S + name)", "Unknown field: ");
        getType.endControlFlow();

        clazz.addMethod(getType.build());

        // =========================
        // getProperty(obj)
        // =========================
        MethodSpec.Builder getProperty = MethodSpec.methodBuilder("getProperty")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(ParameterizedTypeName.get(
                        ClassName.get(List.class),
                        WildcardTypeName.subtypeOf(Object.class)
                ))
                .addParameter(ClassName.get(typeElement), "obj");

        getProperty.beginControlFlow("if (obj == null)");
        getProperty.addStatement("return List.of()");
        getProperty.endControlFlow();

        getProperty.addStatement("$T list = new $T<>()", List.class, ArrayList.class);

        for (FieldInfo f : fields) {

            String getter = f.getter;

            if (f.primitive) {
                getProperty.addStatement("list.add($L)", f.variableName);
            } else {
                getProperty.beginControlFlow("if (obj.$L() != null)", getter);
                getProperty.addStatement("list.add($L)", f.variableName);
                getProperty.endControlFlow();
            }
        }

        getProperty.addStatement("return list");
        clazz.addMethod(getProperty.build());

        // =========================
        // getFieldMap()
        // =========================
        MethodSpec.Builder mapMethod = MethodSpec.methodBuilder("getFieldMap")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(ParameterizedTypeName.get(
                        ClassName.get(Map.class),
                        ClassName.get(String.class),
                        ClassName.get(Object.class)
                ))
                .addParameter(ClassName.get(typeElement), "obj");

        mapMethod.beginControlFlow("if (obj == null)");
        mapMethod.addStatement("return Map.of()");
        mapMethod.endControlFlow();

        mapMethod.addStatement("$T map = new $T<>()", Map.class, HashMap.class);

        for (FieldInfo f : fields) {

            String getter = f.getter;

            if (f.primitive) {
                mapMethod.addStatement("map.put($S, obj.$L())", f.name, getter);
            } else {
                mapMethod.beginControlFlow("if (obj.$L() != null)", getter);
                mapMethod.addStatement("map.put($S, obj.$L())", f.name, getter);
                mapMethod.endControlFlow();
            }
        }

        mapMethod.addStatement("return map");
        clazz.addMethod(mapMethod.build());

        // =========================
        // WRITE FILE
        // =========================
        JavaFile.builder(packageName, clazz.build())
                .build()
                .writeTo(processingEnv.getFiler());
    }

    // =========================
    // FIELD EXTRACTION
    // =========================
    private List<FieldInfo> extractFields(TypeElement typeElement, boolean isRecord) {

        List<FieldInfo> fields = new ArrayList<>();

        if (isRecord) {

            for (RecordComponentElement r : typeElement.getRecordComponents()) {
                fields.add(new FieldInfo(r.getSimpleName().toString(), r.asType()));
            }

        } else {

            for (Element e : typeElement.getEnclosedElements()) {

                if (e.getKind() == ElementKind.FIELD) {

                    VariableElement v = (VariableElement) e;

                    if (v.getModifiers().contains(Modifier.STATIC)) continue;

                    fields.add(new FieldInfo(v.getSimpleName().toString(), v.asType()));
                }
            }
        }

        return fields;
    }

    // =========================
    // MODEL
    // =========================
    static class FieldInfo {

        String name;
        String variableName;
        String getter;
        Class<?> type;
        Class<?> elementType;
        boolean primitive;

        FieldInfo(String name, TypeMirror mirror) {

            this.name = name;
            this.variableName = name; // keep lowercase as requested
            this.getter = "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1);

            this.type = Class.class; // placeholder (simplified in processor)
            this.elementType = null;

            this.primitive = mirror.getKind().isPrimitive();
        }
    }
}


//package com.djpa.processor;
//
//import com.djpa.annotations.GenerateFields;
//import com.google.auto.service.AutoService;
//import com.squareup.javapoet.*;
//
//import javax.annotation.processing.AbstractProcessor;
//import javax.annotation.processing.Processor;
//import javax.annotation.processing.RoundEnvironment;
//import javax.annotation.processing.SupportedAnnotationTypes;
//import javax.annotation.processing.SupportedSourceVersion;
//import javax.lang.model.SourceVersion;
//import javax.lang.model.element.*;
//import javax.lang.model.type.TypeKind;
//import javax.lang.model.type.TypeMirror;
//import javax.tools.Diagnostic;
//import java.io.IOException;
//import java.util.*;
//
//@SupportedAnnotationTypes(
//        "com.djpa.annotations.GenerateFields"
//)
//@SupportedSourceVersion(SourceVersion.RELEASE_17)
//@AutoService(Processor.class)
//public class FieldsProcessor extends AbstractProcessor {
//
//    @Override
//    public boolean process(Set<? extends TypeElement> annotations,
//                           RoundEnvironment roundEnv) {
//
//        for (Element element :
//                roundEnv.getElementsAnnotatedWith(GenerateFields.class)) {
//
//            if (!(element instanceof TypeElement typeElement)) {
//                continue;
//            }
//
//            try {
//                generateClass(typeElement);
//            } catch (Exception e) {
//                processingEnv.getMessager().printMessage(
//                        Diagnostic.Kind.ERROR,
//                        e.getMessage()
//                );
//            }
//        }
//
//        return true;
//    }
//
//    private void generateClass(TypeElement typeElement)
//            throws IOException {
//
//        String packageName =
//                processingEnv.getElementUtils()
//                        .getPackageOf(typeElement)
//                        .getQualifiedName()
//                        .toString();
//
//        String originalClassName =
//                typeElement.getSimpleName().toString();
//
//        String generatedClassName =
//                originalClassName + "Fields";
//
//        boolean isRecord =
//                typeElement.getKind() == ElementKind.RECORD;
//
//        TypeSpec.Builder classBuilder =
//                TypeSpec.classBuilder(generatedClassName)
//                        .addModifiers(Modifier.PUBLIC, Modifier.FINAL);
//
//        classBuilder.addMethod(
//                MethodSpec.constructorBuilder()
//                        .addModifiers(Modifier.PRIVATE)
//                        .build()
//        );
//
//        // -------------------------------
//        // STEP 1: collect fields
//        // -------------------------------
//        List<FieldInfo> fields = new ArrayList<>();
//
//        if (isRecord) {
//
//            for (RecordComponentElement record :
//                    typeElement.getRecordComponents()) {
//
//                fields.add(new FieldInfo(
//                        record.getSimpleName().toString(),
//                        record.asType()
//                ));
//            }
//
//        } else {
//
//            for (Element enclosed :
//                    typeElement.getEnclosedElements()) {
//
//                if (enclosed.getKind() == ElementKind.FIELD) {
//
//                    VariableElement field =
//                            (VariableElement) enclosed;
//
//                    if (field.getModifiers().contains(Modifier.STATIC)) {
//                        continue;
//                    }
//
//                    fields.add(new FieldInfo(
//                            field.getSimpleName().toString(),
//                            field.asType()
//                    ));
//                }
//            }
//        }
//
//        // -------------------------------
//        // STEP 2: constants
//        // -------------------------------
//        for (FieldInfo field : fields) {
//
//            classBuilder.addField(
//                    FieldSpec.builder(
//                                    String.class,
//                                    field.constantName,
//                                    Modifier.PUBLIC,
//                                    Modifier.STATIC,
//                                    Modifier.FINAL
//                            )
//                            .initializer("$S", field.name)
//                            .build()
//            );
//        }
//
//        // -------------------------------
//        // STEP 3: ALL_FIELDS
//        // -------------------------------
//        CodeBlock.Builder listBuilder = CodeBlock.builder();
//        listBuilder.add("List.of(");
//
//        for (int i = 0; i < fields.size(); i++) {
//
//            listBuilder.add(fields.get(i).constantName);
//
//            if (i < fields.size() - 1) {
//                listBuilder.add(", ");
//            }
//        }
//
//        listBuilder.add(")");
//
//        classBuilder.addField(
//                FieldSpec.builder(
//                                ParameterizedTypeName.get(
//                                        ClassName.get(List.class),
//                                        ClassName.get(String.class)
//                                ),
//                                "ALL_FIELDS",
//                                Modifier.PUBLIC,
//                                Modifier.STATIC,
//                                Modifier.FINAL
//                        )
//                        .initializer(listBuilder.build())
//                        .build()
//        );
//
//        // -------------------------------
//        // STEP 4: getFieldMap()
//        // -------------------------------
//        MethodSpec.Builder method =
//                MethodSpec.methodBuilder("getFieldMap")
//                        .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
//                        .returns(ParameterizedTypeName.get(
//                                ClassName.get(Map.class),
//                                ClassName.get(String.class),
//                                ClassName.get(Object.class)
//                        ))
//                        .addParameter(
//                                ClassName.get(typeElement),
//                                "obj"
//                        );
//
//        // null check
//        method.beginControlFlow("if (obj == null)");
//        method.addStatement("return $T.emptyMap()", Collections.class);
//        method.endControlFlow();
//
//        method.addStatement(
//                "$T<$T, $T> map = new $T<>()",
//                Map.class,
//                String.class,
//                Object.class,
//                HashMap.class
//        );
//
//        // field mapping
//        for (FieldInfo field : fields) {
//
//            String accessor;
//
//            if (isRecord) {
//                accessor = "obj." + field.name + "()";
//            } else {
//                accessor = "obj." + field.getterName + "()";
//            }
//
//            if (field.primitive) {
//                method.addStatement("map.put($L, $L)", field.constantName, accessor);
//            } else {
//                method.beginControlFlow("if ($L != null)", accessor);
//                method.addStatement("map.put($L, $L)", field.constantName, accessor);
//                method.endControlFlow();
//            }
//        }
//
//        method.addStatement("return map");
//
//        classBuilder.addMethod(method.build());
//
//        // -------------------------------
//        // STEP 5: write file ONCE
//        // -------------------------------
//        JavaFile.builder(
//                packageName,
//                classBuilder.build()
//        ).build().writeTo(processingEnv.getFiler());
//    }
//
//    private static String getterName(String fieldName, TypeMirror type) {
//        String suffix =
//                Character.toUpperCase(fieldName.charAt(0)) +
//                        fieldName.substring(1);
//
//        if (type.getKind() == TypeKind.BOOLEAN) {
//            return "is" + suffix;
//        }
//
//        return "get" + suffix;
//    }
//
//    private static String constantName(String fieldName) {
//        StringBuilder builder = new StringBuilder();
//
//        for (int i = 0; i < fieldName.length(); i++) {
//            char current = fieldName.charAt(i);
//
//            if (Character.isUpperCase(current) && i > 0) {
//                builder.append('_');
//            }
//
//            if (Character.isLetterOrDigit(current)) {
//                builder.append(Character.toUpperCase(current));
//            } else {
//                builder.append('_');
//            }
//        }
//
//        return builder.toString();
//    }
//
//    // -------------------------------
//    // helper class
//    // -------------------------------
//    static class FieldInfo {
//        String name;
//        String constantName;
//        String getterName;
//        TypeMirror type;
//        boolean primitive;
//
//        FieldInfo(String name, TypeMirror type) {
//            this.name = name;
//            this.constantName = constantName(name);
//            this.getterName = getterName(name, type);
//            this.type = type;
//            this.primitive = type.getKind().isPrimitive();
//        }
//    }
//}
