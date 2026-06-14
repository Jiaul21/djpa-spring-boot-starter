package com.djpa.processor;

import com.djpa.annotations.GenerateFields;
import com.djpa.annotations.FieldProperty;
import com.google.auto.service.AutoService;
import com.squareup.javapoet.*;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.*;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.util.*;

@AutoService(Processor.class)
@SupportedAnnotationTypes("com.djpa.annotations.GenerateFields")
@SupportedSourceVersion(SourceVersion.RELEASE_17)
public class FieldsProcessor extends AbstractProcessor {

    private Types typeUtils;

    public FieldsProcessor() {
        super();
    }

    @Override
    public synchronized void init(ProcessingEnvironment env) {
        super.init(env);
        this.typeUtils = env.getTypeUtils();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {

        for (Element e : roundEnv.getElementsAnnotatedWith(GenerateFields.class)) {
            if (!(e instanceof TypeElement type)) continue;

            try {
                generate(type);
            } catch (Exception ex) {
                processingEnv.getMessager().printMessage(
                        Diagnostic.Kind.ERROR,
                        ex.toString()
                );
            }
        }
        return true;
    }

    // =========================
    // MAIN GENERATION
    // =========================
    private void generate(TypeElement type) throws IOException {

        String pkg = processingEnv.getElementUtils()
                .getPackageOf(type)
                .getQualifiedName().toString();

        String className = type.getSimpleName() + "Fields";

        TypeSpec.Builder clazz = TypeSpec.classBuilder(className)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL);

        clazz.addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PRIVATE)
                .build());

        List<FieldMeta> fields = extractFields(type);

        ClassName fieldProperty = ClassName.get(FieldProperty.class);

        List<String> fieldNames = new ArrayList<>();

        // =========================
        // FIELD CONSTANTS
        // =========================
        for (FieldMeta f : fields) {

            TypeName mainType = TypeName.get(f.type);

            TypeName elemType = f.elementType != null
                    ? TypeName.get(f.elementType)
                    : ClassName.get(Void.class);

            String name = f.name;

            FieldSpec field = FieldSpec.builder(
                            ParameterizedTypeName.get(fieldProperty, mainType, elemType),
                            name,
                            Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL
                    )
                    .initializer(buildFieldInit(fieldProperty, name, mainType, elemType, f.isCollection))
                    .build();

            clazz.addField(field);
            fieldNames.add(name);
        }

        // =========================
        // ALL_FIELDS
        // =========================
        TypeName listType = ParameterizedTypeName.get(
                ClassName.get(List.class),
                WildcardTypeName.subtypeOf(
                        ParameterizedTypeName.get(fieldProperty,
                                WildcardTypeName.subtypeOf(Object.class),
                                WildcardTypeName.subtypeOf(Object.class)
                        )
                )
        );

        String allFieldsInit = String.join(", ", fieldNames);

        clazz.addField(FieldSpec.builder(listType, "ALL_FIELDS",
                        Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer("List.of($L)", allFieldsInit)
                .build());

        // =========================
        // getType
        // =========================
        MethodSpec.Builder getType = MethodSpec.methodBuilder("getType")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(Class.class)
                .addParameter(String.class, "name");

        getType.beginControlFlow("return switch(name)");

        for (FieldMeta f : fields) {
            getType.addCode("case $S -> $T.class;\n",
                    f.name, TypeName.get(f.type));
        }

        getType.addCode("default -> throw new IllegalArgumentException(\"Unknown field: \" + name);\n");
        getType.endControlFlow();

        clazz.addMethod(getType.build());

        // =========================
        // getFieldMap
        // =========================
        TypeName mapType = ParameterizedTypeName.get(
                ClassName.get(Map.class),
                ClassName.get(String.class),
                ClassName.get(Object.class)
        );

        MethodSpec.Builder map = MethodSpec.methodBuilder("getFieldMap")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(mapType)
                .addParameter(TypeName.get(type.asType()), "obj");

        map.beginControlFlow("if (obj == null)")
                .addStatement("return Map.of()")
                .endControlFlow();

        map.addStatement("$T m = new $T<>()", Map.class, HashMap.class);

        for (FieldMeta f : fields) {

            String getter = getterName(f);

            if (f.isPrimitive) {
                map.addStatement("m.put($S, obj.$L())", f.name, getter);
            } else {
                map.beginControlFlow("if (obj.$L() != null)", getter)
                        .addStatement("m.put($S, obj.$L())", f.name, getter)
                        .endControlFlow();
            }
        }

        map.addStatement("return m");
        clazz.addMethod(map.build());

        JavaFile.builder(pkg, clazz.build())
                .build()
                .writeTo(processingEnv.getFiler());
    }

    // =========================
    // FIELD INIT
    // =========================
    private CodeBlock buildFieldInit(
            ClassName fieldProperty,
            String name,
            TypeName mainType,
            TypeName elemType,
            boolean isCollection
    ) {
        if (isCollection) {
            return CodeBlock.of(
                    "new $T<>($S, $T.class, $T.class)",
                    fieldProperty,
                    name,
                    mainType,
                    elemType
            );
        }

        return CodeBlock.of(
                "new $T<>($S, $T.class, null)",
                fieldProperty,
                name,
                mainType
        );
    }

    // =========================
    // SAFE GETTER
    // =========================
    private String getterName(FieldMeta f) {
        String n = f.name;
        if (f.type.getKind() == TypeKind.BOOLEAN) {
            return "is" + capitalize(n);
        }
        return "get" + capitalize(n);
    }

    private String capitalize(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // =========================
    // FIELD EXTRACTION
    // =========================
    private List<FieldMeta> extractFields(TypeElement type) {

        List<FieldMeta> list = new ArrayList<>();

        for (Element e : type.getEnclosedElements()) {

            if (e.getKind() != ElementKind.FIELD) continue;
            if (e.getModifiers().contains(Modifier.STATIC)) continue;

            list.add(new FieldMeta((VariableElement) e));
        }

        return list;
    }

    // =========================
    // META MODEL
    // =========================
    static class FieldMeta {

        String name;
        TypeMirror type;
        TypeMirror elementType;
        boolean isPrimitive;
        boolean isCollection;

        FieldMeta(VariableElement v) {
            this.name = v.getSimpleName().toString();
            this.type = v.asType();

            this.isPrimitive = type.getKind().isPrimitive();
            this.isCollection = isCollection(type);

            this.elementType = extractElement(type);
        }

        private boolean isCollection(TypeMirror t) {
            String s = t.toString();
            return s.startsWith("java.util.List")
                    || s.startsWith("java.util.Set")
                    || s.startsWith("java.util.Collection");
        }

        private TypeMirror extractElement(TypeMirror t) {
            if (t instanceof DeclaredType dt &&
                    !dt.getTypeArguments().isEmpty()) {
                return dt.getTypeArguments().get(0);
            }
            return null;
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
