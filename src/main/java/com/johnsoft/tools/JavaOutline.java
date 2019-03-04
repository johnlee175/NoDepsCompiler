/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache license, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the license for the specific language governing permissions and
 * limitations under the license.
 */
package com.johnsoft.tools;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;

/**
 * Create class file which include class/method outline of java source code
 *
 * Generics/Annotations/Enums and field are not supported at this time
 *
 * Gradle: implementation 'com.github.javaparser:javaparser-core:3.13.1'
 *
 * @author John Kenrinus Lee
 * @version 2019-03-01
 */
@SuppressWarnings({ "unused", "WeakerAccess" })
public class JavaOutline {
    public static final String SIMPLE_CLASS_NAME = "SimpleClassName";

    private final HashMap<String, String> nameSearchMap = new HashMap<>();
    private final IdentityHashMap<ClassOrInterfaceDeclaration, NoDepsCompiler.Class> classMap = new IdentityHashMap<>();
    private String packageName;

    public void parse(String filePath) throws IOException {
        JavaParser parser = new JavaParser();
        ParseResult<CompilationUnit> unitParseResult = parser.parse(new File(filePath), Charset.forName("UTF-8"));
        if (unitParseResult.isSuccessful()) {
            unitParseResult.getResult().ifPresent(this::visit);
        }
    }

    public Collection<NoDepsCompiler.Class> getParsedClasses() {
        return classMap.values();
    }

    protected void visit(CompilationUnit unit) {
        unit.getPackageDeclaration()
            .ifPresent(packageDeclaration -> packageName = packageDeclaration.getNameAsString());
        unit.getImports().forEach(importDeclaration -> {
            String importString = importDeclaration.getNameAsString();
            nameSearchMap.put(importString.substring(importString.lastIndexOf('.') + 1), importString);
        });
        unit.getTypes().forEach(typeDeclaration -> {
            String typeString = typeDeclaration.getNameAsString();
            final String fullName;
            if (packageName != null && !packageName.trim().isEmpty()) {
                fullName = packageName + '.' + typeString;
            } else {
                fullName = typeString;
            }
            nameSearchMap.put(typeString, fullName);
            if (typeDeclaration instanceof ClassOrInterfaceDeclaration) {
                visit((ClassOrInterfaceDeclaration) typeDeclaration);
            }
        });
    }

    protected void visit(ClassOrInterfaceDeclaration declaration) {
        NoDepsCompiler.Class klass = new NoDepsCompiler.Class();
        if (declaration.isPublic()) {
            klass.setPublic();
        } else if (declaration.isPrivate()) {
            klass.setPrivate();
        } else if (declaration.isProtected()) {
            klass.setProtected();
        }
        if (declaration.isStatic()) {
            klass.setStatic();
        }

        if (declaration.isInterface()) {
            klass.setTypeType(NoDepsCompiler.Class.TypeType.INTERFACE);
        } else {
            klass.setTypeType(NoDepsCompiler.Class.TypeType.CLASS);
        }
        if (declaration.isFinal()) {
            klass.setFinal();
        } else if (declaration.isAbstract()) {
            klass.setAbstract();
        }
        declaration.getExtendedTypes().forEach(type -> {
            klass.setSuper(guessName(type.getNameAsString()));
        });
        klass.setInterfaces(declaration.getImplementedTypes().stream()
                                            .map(type -> guessName(type.getNameAsString()))
                                            .toArray(String[]::new));

        String name = declaration.getNameAsString();
        String fullName = nameSearchMap.get(name);
        if (declaration.isNestedType()) {
            int idx1 = fullName.lastIndexOf('.');
            String parent = fullName.substring(0, idx1);
            int idx2 = parent.lastIndexOf('.');
            String simpleName = idx2 > 0 ? parent.substring(idx2 + 1) : parent;
            klass.putUserData(SIMPLE_CLASS_NAME, simpleName + '$' + name);
            klass.setName(parent + '$' + name);

            klass.getInnerClasses().add(klass);
            for (Map.Entry<ClassOrInterfaceDeclaration, NoDepsCompiler.Class> entry : classMap.entrySet()) {
                if (entry.getKey().getNameAsString().equals(simpleName)) {
                    entry.getValue().getInnerClasses().add(klass);
                }
            }
        } else {
            klass.putUserData(SIMPLE_CLASS_NAME, name);
            klass.setName(fullName == null ? name : fullName);
        }

        classMap.put(declaration, klass);
        declaration.getMembers().stream()
                   .filter(bodyDeclaration -> bodyDeclaration instanceof TypeDeclaration)
                   .forEach(bodyDeclaration -> {
                       TypeDeclaration n = (TypeDeclaration) bodyDeclaration;
                       String m = n.getNameAsString();
                       nameSearchMap.put(m, fullName + '.' + m);
                       if (n instanceof ClassOrInterfaceDeclaration) {
                           visit((ClassOrInterfaceDeclaration) n);
                       }
                   });
        declaration.getMembers().stream()
                   .filter(bodyDeclaration -> !(bodyDeclaration instanceof TypeDeclaration))
                   .forEach(bodyDeclaration -> {
                       if (bodyDeclaration instanceof CallableDeclaration) {
                           visit((CallableDeclaration) bodyDeclaration);
                       } else { // field are not supported at this time
                           System.err.println("Unknown BodyDeclaration: "
                                   + bodyDeclaration.getClass() + " -> " + bodyDeclaration);
                       }
                   });
    }

    protected void visit(CallableDeclaration<?> declaration) {
        declaration.getParentNode().ifPresent(node -> {
            if (node instanceof ClassOrInterfaceDeclaration) {
                NoDepsCompiler.Class klass = classMap.get(node);
                NoDepsCompiler.Method method = new NoDepsCompiler.Method();

                if (declaration.isConstructorDeclaration()) {
                    method.setConstructor();
                } else {
                    MethodDeclaration methodDeclaration = (MethodDeclaration) declaration;
                    method.setName(methodDeclaration.getNameAsString());
                    method.setReturnType(guessName(methodDeclaration.getType().asString()));
                    if (methodDeclaration.isNative()) {
                        method.setNative();
                    }
                    if (methodDeclaration.isFinal()) {
                        method.setFinal();
                    } else if (methodDeclaration.isAbstract()) {
                        method.setAbstract();
                    }
                    if (methodDeclaration.isStatic()) {
                        method.setStatic();
                    }
                }

                if (declaration.isPublic()) {
                    method.setPublic();
                } else if (declaration.isProtected()) {
                    method.setProtected();
                } else if (declaration.isPrivate()) {
                    method.setPrivate();
                }

                method.setParameterTypes(declaration.getParameters().stream()
                                                    .map(parameter -> guessName(parameter.getTypeAsString()))
                                                    .toArray(String[]::new));
                method.setExceptionTypes(declaration.getThrownExceptions().stream()
                                                    .map(referenceType -> guessName(referenceType.asString()))
                                                    .toArray(String[]::new));

                klass.getMethods().add(method);
            }
        });
    }

    protected String guessName(String name) {
        if (name.contains(".")) { // with package
            return name;
        }
        String fullName = nameSearchMap.get(name);
        if (fullName != null) {
            return fullName;
        }
        return "java.lang." + name;
    }

    public static void main(String[] args) throws IOException {
        final String path = args[0];
        JavaOutline outline = new JavaOutline();
        outline.parse(path);
        outline.getParsedClasses().forEach(klass -> {
            NoDepsCompiler compiler = new NoDepsCompiler(8, false);
            compiler.createClassToPath(klass, path.substring(0, path.lastIndexOf('/') + 1)
                    + klass.getUserData(SIMPLE_CLASS_NAME, null).toString() + ".class", true);
        });
    }
}
