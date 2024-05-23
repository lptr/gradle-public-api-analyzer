package org.gradle.research;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Maps;
import com.google.common.collect.MultimapBuilder;
import com.ibm.wala.classLoader.BinaryDirectoryTreeModule;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.core.util.config.AnalysisScopeReader;
import com.ibm.wala.ipa.callgraph.AnalysisScope;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.ipa.cha.ClassHierarchyException;
import com.ibm.wala.ipa.cha.ClassHierarchyFactory;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.types.TypeReference;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ReportGenerator {
    private final ApiTypeFilter apiTypeFilter;
    private final List<File> classpath;
    private final PrintWriter writer;

    public ReportGenerator(ApiTypeFilter apiTypeFilter, List<File> classpath, PrintWriter writer) {
        this.apiTypeFilter = apiTypeFilter;
        this.classpath = classpath;
        this.writer = writer;
    }

    public void generateReport() throws IOException, ClassHierarchyException {
        AnalysisScope scope = createScope(classpath);
        ClassHierarchy hierarchy = ClassHierarchyFactory.make(scope);
        ListMultimap<String, IClass> packagesToTypes = MultimapBuilder.treeKeys().arrayListValues().build();
        Comparator<IClass> classComparator = Comparator.comparing(clazz -> clazz.getName().getClassName().toString());
        ListMultimap<IClass, IMethod> typesToMethods = MultimapBuilder.treeKeys(classComparator).arrayListValues().build();
        Map<IClass, Map<String, Property>> typesToProperties = Maps.newTreeMap(classComparator);
        for (IClass iClass : hierarchy) {
            // Skip non-public types
            if (!iClass.isPublic()) {
                continue;
            }
            // Skip anonymous inner classes
            if (iClass.getName().getClassName().toString().contains("$")) {
                continue;
            }
            // Skip internal APIs
            if (!apiTypeFilter.includeType(iClass)) {
                continue;
            }

            packagesToTypes.put(String.valueOf(iClass.getName().getPackage()), iClass);
            for (IMethod declaredMethod : iClass.getDeclaredMethods()) {
                if (!declaredMethod.isPublic()) {
                    continue;
                }
                typesToMethods.put(iClass, declaredMethod);

                if (declaredMethod.isInit() || declaredMethod.isClinit()) {
                    continue;
                }

                PropertyMethod.from(declaredMethod)
                    .ifPresent(propertyMethod ->
                        typesToProperties.computeIfAbsent(iClass, __ -> new TreeMap<>())
                            .computeIfAbsent(propertyMethod.propertyName(), __ -> new Property())
                            .addPropertyMethod(propertyMethod));
            }
        }

        printHeader("Summary");
        writer.println("- Packages: " + packagesToTypes.keySet().size());
        writer.println("- Types: " + packagesToTypes.size());
        writer.println("- Methods: " + typesToMethods.size());
        writer.println("- Properties: " + typesToProperties.values().stream().mapToInt(Map::size).sum());

        printHeader("Setters without getters");
        forEachProperty(typesToProperties, (type, propertyName, property) -> {
            if (property.getter == null) {
                property.setters.forEach(setter ->
                    writer.printf("- `%s`%n", toSimpleSignature(setter))
                );
            }
        });

        var propertiesWithInconsistentSetters = new ArrayList<String>();
        var propertiesWithAdditionalSetters = new ArrayList<String>();
        forEachProperty(typesToProperties, (type, propertyName, property) -> {
            // Ignore properties without a getter
            if (property.getter == null) {
                return;
            }
            ImmutableSet<TypeReference> types = property.collectTypes();
            if (types.size() != 1) {
                String inconsistentGetterDescription = String.format("- `%s` (setter: %s)",
                    toSimpleSignature(property.getter),
                    types.stream().filter(Predicate.not(property.getter.getReturnType()::equals))
                        .map(ReportGenerator::toSimpleName)
                        .map("`%s`"::formatted)
                        .collect(Collectors.joining(", ")));

                property.matchingGetterAndSetterType()
                    .ifPresentOrElse(
                        matchingType -> propertiesWithAdditionalSetters.add(inconsistentGetterDescription),
                        () -> propertiesWithInconsistentSetters.add(inconsistentGetterDescription)
                    );
            }
        });

        printHeader("Properties with inconsistent getter/setter types");
        propertiesWithInconsistentSetters.forEach(writer::println);

        printHeader("Properties with consistent getter/setter types, but with additional setter types");
        propertiesWithAdditionalSetters.forEach(writer::println);

        printHeader("Properties with `propertyName()` setters");
        forEachProperty(typesToProperties, (type, propertyName, property) ->
            typesToMethods.get(type).stream()
                .filter(Predicate.not(IMethod::isStatic))
                .filter(method -> method.getNumberOfParameters() == 2)
                .filter(method -> method.getName().toString().equals(propertyName))
                .filter(method -> {
                    TypeReference parameterType = method.getParameterType(1);
                    return !parameterType.getName().toString().equals("Lgroovy/lang/Closure")
                           && !parameterType.getName().toString().equals("Lorg/gradle/api/Action");
                })
                .findFirst()
                .ifPresent(weirdSetter -> writer.printf("- `%s`%n", toSimpleSignature(weirdSetter))));

        printHeader("Fluent setters");
        forEachProperty(typesToProperties, (type, propertyName, property) ->
            property.setters.stream()
                .filter(setter -> !setter.getReturnType().equals(TypeReference.Void))
                .forEach(setter -> writer.printf("- `%s`%n", toSimpleSignature(setter)))
        );

        printHeader("Lazy properties with non-abstract getters");
        IClass providerType = hierarchy.lookupClass(TypeReference.findOrCreate(ClassLoaderReference.Application, "Lorg/gradle/api/provider/Provider"));
        // TODO This should probably be FileCollection to match Provider
        IClass configurableFileCollectionType = hierarchy.lookupClass(TypeReference.findOrCreate(ClassLoaderReference.Application, "Lorg/gradle/api/file/ConfigurableFileCollection"));
        forEachProperty(typesToProperties, (type, propertyName, property) -> {
            if (property.getter == null) {
                return;
            }
            if (type.isInterface()) {
                return;
            }
            if (property.getter.isAbstract()) {
                return;
            }
            IClass getterType = hierarchy.lookupClass(property.getter.getReturnType());
            if (getterType == null) {
                return;
            }
            if (hierarchy.isAssignableFrom(providerType, getterType)
                || hierarchy.isAssignableFrom(configurableFileCollectionType, getterType)) {
                writer.printf("- `%s`%n", toSimpleSignature(property.getter));
            }
        });
    }

    private void printHeader(String header) {
        writer.println();
        writer.println("## " + header);
        writer.println();
    }

    private static void forEachProperty(Map<IClass, Map<String, Property>> properties, TriConsumer<IClass, String, Property> consumer) {
        properties.forEach((iClass, classProperties) -> classProperties.forEach((propertyName, property) ->
            consumer.accept(iClass, propertyName, property)));
    }

    private interface TriConsumer<T, U, V> {
        void accept(T t, U u, V v);
    }

    private static String toSimpleSignature(IMethod method) {
        // Get the class name
        String className = toSimpleName(method.getDeclaringClass().getReference());

        // Get the method name
        String methodName = method.getName().toString();

        // Get the parameter types
        String parameterTypes = Stream.iterate(method.isStatic() ? 0 : 1, i -> i < method.getNumberOfParameters(), i -> i + 1)
            .map(method::getParameterType)
            .map(ReportGenerator::toSimpleName)
            .collect(Collectors.joining(", "));

        // Get the return type
        String returnType = toSimpleName(method.getReturnType());

        return String.format("%s %s.%s(%s)", returnType, className, methodName, parameterTypes);
    }

    private static String toSimpleName(TypeReference typeReference) {
        String simpleName = toSimpleTypeName(typeReference);
        if (typeReference.isArrayType()) {
            return toSimpleName(typeReference.getArrayElementType()) + "[]";
        } else {
            return simpleName;
        }
    }

    private static String toSimpleTypeName(TypeReference typeReference) {
        if (typeReference.equals(TypeReference.Void)) {
            return "void";
        }
        if (typeReference.isPrimitiveType()) {
            if (typeReference.equals(TypeReference.Byte)) {
                return "byte";
            }
            if (typeReference.equals(TypeReference.Char)) {
                return "char";
            }
            if (typeReference.equals(TypeReference.Double)) {
                return "double";
            }
            if (typeReference.equals(TypeReference.Float)) {
                return "float";
            }
            if (typeReference.equals(TypeReference.Int)) {
                return "int";
            }
            if (typeReference.equals(TypeReference.Long)) {
                return "long";
            }
            if (typeReference.equals(TypeReference.Short)) {
                return "short";
            }
            if (typeReference.equals(TypeReference.Boolean)) {
                return "boolean";
            }
        }
        String name = typeReference.getName().toString();
        // Remove package part if present
        if (name.contains("/")) {
            name = name.substring(name.lastIndexOf('/') + 1);
        }
        return name.replace(';', ' ').trim();
    }

    private sealed interface PropertyMethod {
        String propertyName();

        static Optional<PropertyMethod> from(IMethod method) {
            if (method.isStatic()) {
                return Optional.empty();
            }
            String methodName = method.getName().toString();
            if (method.getNumberOfParameters() == 1 && !method.getReturnType().equals(TypeReference.Void)) {
                if (methodName.startsWith("get") && methodName.length() > 3) {
                    return Optional.of(new Getter(getPropertyName(methodName, 3), method));
                }
                if (methodName.startsWith("is") && methodName.length() > 2) {
                    return Optional.of(new Getter(getPropertyName(methodName, 2), method));
                }
            }
            if (method.getNumberOfParameters() == 2 && methodName.startsWith("set") && methodName.length() > 3) {
                return Optional.of(new Setter(getPropertyName(methodName, 3), method));
            }
            return Optional.empty();
        }
    }

    private static String getPropertyName(String methodName, int prefixLength) {
        return Character.toLowerCase(methodName.charAt(prefixLength)) + methodName.substring(prefixLength + 1);
    }

    private record Getter(String propertyName, IMethod method) implements PropertyMethod {
    }

    private record Setter(String propertyName, IMethod method) implements PropertyMethod {
    }

    private static class Property {
        private IMethod getter;
        private final List<IMethod> setters = new ArrayList<>();

        public void addPropertyMethod(PropertyMethod propertyMethod) {
            switch (propertyMethod) {
                case Getter getterMethod -> this.getter = getterMethod.method();
                case Setter setterMethod -> this.setters.add(setterMethod.method());
            }
        }

        public ImmutableSet<TypeReference> collectTypes() {
            return Stream.concat(
                    Stream.ofNullable(getter)
                        .map(IMethod::getReturnType),
                    setters.stream()
                        .map(method -> method.getParameterType(1))
                )
                .collect(ImmutableSet.toImmutableSet());
        }

        public Optional<TypeReference> matchingGetterAndSetterType() {
            if (getter == null) {
                return Optional.empty();
            }
            TypeReference getterType = getter.getReturnType();
            return setters.stream()
                .map(method -> method.getParameterType(1))
                .filter(setterType -> setterType.equals(getterType))
                .findFirst();
        }
    }

    private static AnalysisScope createScope(Collection<File> classpath) throws IOException {
        AnalysisScope scope = AnalysisScopeReader.instance.makePrimordialScope(null);
        classpath.forEach(classpathEntry -> addToScope(scope, classpathEntry));
        return scope;
    }

    private static void addToScope(AnalysisScope scope, File classpathEntry) {
        ClassLoaderReference loader = scope.getLoader(AnalysisScope.APPLICATION);
        if (Files.isRegularFile(classpathEntry.toPath())) {
            try {
                JarFile jar = new JarFile(classpathEntry, false);
                scope.addToScope(loader, jar);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        } else {
            scope.addToScope(loader, new BinaryDirectoryTreeModule(classpathEntry));
        }
    }
}
