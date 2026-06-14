package com.djpa.processor;

import com.djpa.annotations.FieldProperty;
import com.djpa.annotations.GenerateFields;
import com.google.auto.service.AutoService;
import com.squareup.javapoet.*;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
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

            TypeName mainType = getRawTypeName(f.type);
            TypeName elemType = f.elementType != null ? TypeName.get(f.elementType) : ClassName.get(Void.class);

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
        // FIELD_MAP
        // =========================
        TypeName fieldPropertyType = ParameterizedTypeName.get(
                ClassName.get(FieldProperty.class),
                WildcardTypeName.subtypeOf(Object.class),
                WildcardTypeName.subtypeOf(Object.class)
        );
        TypeName mapFieldType = ParameterizedTypeName.get(
                ClassName.get(Map.class),
                ClassName.get(String.class),
                fieldPropertyType
        );
        clazz.addField(FieldSpec.builder(mapFieldType, "FIELD_MAP", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL).build());


        // =========================
        // static inititalizer
        // =========================
        CodeBlock.Builder staticBlock = CodeBlock.builder();
        staticBlock.addStatement("$T<String, $T> m = new $T<>()", Map.class, fieldPropertyType, LinkedHashMap.class);

        for (FieldMeta f : fields) {
            staticBlock.addStatement("m.put($L.name(), $L)", f.name, f.name);
        }

        staticBlock.addStatement("FIELD_MAP = $T.unmodifiableMap(m)", Collections.class);
        clazz.addStaticBlock(staticBlock.build());

        // =========================
        // getProperty
        // =========================
        MethodSpec getProperty = MethodSpec.methodBuilder("getProperty")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(fieldPropertyType)
                .addParameter(String.class, "fieldName")
                .addStatement("$T property = FIELD_MAP.get(fieldName)", fieldPropertyType)
                .beginControlFlow("if (property == null)")
                .addStatement(
                        "throw new IllegalArgumentException($S + fieldName)",
                        "Unknown field: "
                )
                .endControlFlow()
                .addStatement("return property")
                .build();

        clazz.addMethod(getProperty);

        // =========================
        // getType
        // =========================
        MethodSpec getType = MethodSpec.methodBuilder("getType")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(
                        ParameterizedTypeName.get(
                                ClassName.get(Class.class),
                                WildcardTypeName.subtypeOf(Object.class)
                        )
                )
                .addParameter(String.class, "fieldName")
                .addStatement("return getProperty(fieldName).type()")
                .build();

        clazz.addMethod(getType);



//        list lookup
//        MethodSpec getType = MethodSpec.methodBuilder("getType")
//                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
//                .returns(ParameterizedTypeName.get(
//                        ClassName.get(Class.class),
//                        WildcardTypeName.subtypeOf(Object.class)
//                ))
//                .addParameter(String.class, "fieldName")
//                .addStatement("""
//                                return ALL_FIELDS.stream()
//                                        .filter(f -> f.name().equals(fieldName))
//                                        .findFirst()
//                                        .map($T::type)
//                                        .orElseThrow(() ->
//                                                new IllegalArgumentException("Unknown field: " + fieldName))
//                                """,
//                        FieldProperty.class)
//                .build();
//
//        clazz.addMethod(getType);



        // old way

//        MethodSpec.Builder getType = MethodSpec.methodBuilder("getType")
//                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
//                .returns(Class.class)
//                .addParameter(String.class, "name");
//        getType.beginControlFlow("return switch(name)");
//        for (FieldMeta f : fields) {
//            TypeName classType;
//            if (f.isCollection) {
//                DeclaredType dt = (DeclaredType) f.type;
//                classType = ClassName.get((TypeElement) dt.asElement());
//            } else {
//                classType = TypeName.get(f.type);
//            }
//            getType.addCode("case $S -> $T.class;\n", f.name, classType);
//        }
//        getType.addCode("default -> throw new IllegalArgumentException(\"Unknown field: \" + name);\n");
//        getType.endControlFlow();
//        clazz.addMethod(getType.build());


        // =========================
        // getPropertyValue
        // =========================
        TypeName fieldPropertyValue = ParameterizedTypeName.get(
                ClassName.get(FieldProperty.class),
                WildcardTypeName.subtypeOf(Object.class),
                WildcardTypeName.subtypeOf(Object.class)
        );

        TypeName returnType = ParameterizedTypeName.get(ClassName.get(Map.class), fieldPropertyValue, ClassName.get(Object.class));
        MethodSpec.Builder method = MethodSpec.methodBuilder("getPropertyValue")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC).returns(returnType).addParameter(TypeName.get(type.asType()), "obj");

        method.beginControlFlow("if (obj == null)").addStatement("return $T.of()", Map.class).endControlFlow();
        method.addStatement("$T<$T, Object> map = new $T<>()", Map.class, fieldPropertyValue, LinkedHashMap.class);

        for (FieldMeta f : fields) {
            String getter = getterName(f);
            if (f.isPrimitive) {
                method.addStatement("map.put($L, obj.$L())", f.name, getter);
            } else {
                method.beginControlFlow("if (obj.$L() != null)", getter)
                        .addStatement("map.put($L, obj.$L())", f.name, getter)
                        .endControlFlow();
            }
        }
        method.addStatement("return map");
        clazz.addMethod(method.build());


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

//        map.addStatement("$T m = new $T<>()", Map.class, HashMap.class);
        map.addStatement("$T<String, Object> m = new $T<>()", Map.class, HashMap.class);

        for (FieldMeta f : fields) {

            String getter = getterName(f);

            if (f.isPrimitive) {
//                map.addStatement("m.put($S, obj.$L())", f.name, getter);
                map.addStatement("m.put($L.name(), obj.$L())", f.name, getter);
            } else {
//                map.beginControlFlow("if (obj.$L() != null)", getter)
//                        .addStatement("m.put($S, obj.$L())", f.name, getter)
//                        .endControlFlow();
                map.beginControlFlow("if (obj.$L() != null)", getter)
                        .addStatement("m.put($L.name(), obj.$L())", f.name, getter)
                        .endControlFlow();
            }
        }

        map.addStatement("return m");
        clazz.addMethod(map.build());

        JavaFile.builder(pkg, clazz.build())
                .build()
                .writeTo(processingEnv.getFiler());
    }

    private TypeName getRawTypeName(TypeMirror t) {
        if (t instanceof DeclaredType dt) {
            return ClassName.get((TypeElement) dt.asElement());
        }
        return TypeName.get(t);
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
