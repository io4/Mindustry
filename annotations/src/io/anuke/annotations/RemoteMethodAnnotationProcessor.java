package io.anuke.annotations;

import com.squareup.javapoet.*;
import io.anuke.annotations.Annotations.Local;
import io.anuke.annotations.Annotations.RemoteClient;
import io.anuke.annotations.Annotations.RemoteServer;
import io.anuke.annotations.Annotations.Unreliable;
import io.anuke.annotations.IOFinder.ClassSerializer;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic.Kind;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Set;

//TODO document
//TODO split up into more classes
//TODO somehow use annotations to generate serializers for each type?
//TODO documentation
//TODO custom hash to verify server/client compatibility, just in case!
//TODO specify creation class for putting each method
//TODO unified Call.functionName() class for more unified usage
//TODO error reporting for invalid usage, e.g. method IDs
//TODO automatically disable calling on server/client when it's not necessary
//TODO autogenerate methods for calling functions for specific clients
@SupportedSourceVersion(SourceVersion.RELEASE_8)
@SupportedAnnotationTypes({
    "io.anuke.annotations.Annotations.RemoteClient",
    "io.anuke.annotations.Annotations.RemoteServer",
    "io.anuke.annotations.Annotations.Local",
    "io.anuke.annotations.Annotations.Unreliable",
    "io.anuke.annotations.Annotations.In",
    "io.anuke.annotations.Annotations.WriteClass",
    "io.anuke.annotations.Annotations.ReadClass",
})
public class RemoteMethodAnnotationProcessor extends AbstractProcessor {
    /**Maximum size of each event packet.*/
    private static final int maxPacketSize = 512;
    /**Name of the base package to put all the generated classes.*/
    private static final String packageClassName = "io.anuke.mindustry.gen";

    /**Maps fully qualified class names to serializers.*/
    private HashMap<String, ClassSerializer> serializers;
    /**Whether the initial round is done.*/
    private boolean done;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        //put all relevant utils into utils class
        Utils.typeUtils = processingEnv.getTypeUtils();
        Utils.elementUtils = processingEnv.getElementUtils();
        Utils.filer = processingEnv.getFiler();
        Utils.messager = processingEnv.getMessager();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if(done) return false;
        done = true;

        serializers = new IOFinder().findSerializers(roundEnv);

        writeElements(roundEnv, clientFullClassName, RemoteClient.class);
        writeElements(roundEnv, serverFullClassName, RemoteServer.class);

        return true;
    }

    private void writeElements(RoundEnvironment env){
        try {
            boolean client = annotation == RemoteServer.class;
            String className = fullClassName.substring(1 + fullClassName.lastIndexOf('.'));
            String packageName = fullClassName.substring(0, fullClassName.lastIndexOf('.'));

            Constructor<TypeName> cons = TypeName.class.getDeclaredConstructor(String.class);
            cons.setAccessible(true);

            TypeName playerType = cons.newInstance("io.anuke.mindustry.entities.Player");

            TypeSpec.Builder classBuilder = TypeSpec.classBuilder(className).addModifiers(Modifier.PUBLIC);

            int id = 0;

            classBuilder.addField(FieldSpec.builder(ByteBuffer.class, "TEMP_BUFFER", Modifier.STATIC, Modifier.PRIVATE, Modifier.FINAL)
                    .initializer("ByteBuffer.allocate($1L)", maxPacketSize).build());

            MethodSpec.Builder readMethod = MethodSpec.methodBuilder("readPacket")
                    .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                    .addParameter(ByteBuffer.class, "buffer")
                    .addParameter(int.class, "id")
                    .returns(void.class);

            if(client){
                readMethod.addParameter(playerType, "player");
            }

            CodeBlock.Builder writeSwitch = CodeBlock.builder();
            boolean started = false;

            readMethod.addJavadoc("This method reads and executes a method by ID. For internal use only!");

            for (Element e : env.getElementsAnnotatedWith(annotation)) {
                if(!e.getModifiers().contains(Modifier.STATIC)) {
                    messager.printMessage(Kind.ERROR, "All local/remote methods must be static: ", e);
                }else if(e.getKind() != ElementKind.METHOD){
                    messager.printMessage(Kind.ERROR, "All local/remote annotations must be on methods: ", e);
                }

                if(e.getAnnotation(annotation) == null) continue;
                boolean local = e.getAnnotation(Local.class) != null;
                boolean unreliable = e.getAnnotation(Unreliable.class) != null;

                ExecutableElement exec = (ExecutableElement)e;

                MethodSpec.Builder method = MethodSpec.methodBuilder(e.getSimpleName().toString())
                        .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                        .returns(void.class);

                if(client){
                    if(exec.getParameters().isEmpty()){
                        messager.printMessage(Kind.ERROR, "Client invoke methods must have a first parameter of type Player.", e);
                        return;
                    }

                    VariableElement var = exec.getParameters().get(0);

                    if(!var.asType().toString().equals("io.anuke.mindustry.entities.Player")){
                        messager.printMessage(Kind.ERROR, "Client invoke methods should have a first parameter of type Player.", e);
                    }
                }

                for(VariableElement var : exec.getParameters()){
                    method.addParameter(TypeName.get(var.asType()), var.getSimpleName().toString());
                }

                if(local){
                    int index = 0;
                    StringBuilder results = new StringBuilder();
                    for(VariableElement var : exec.getParameters()){
                        results.append(var.getSimpleName());
                        if(index != exec.getParameters().size() - 1) results.append(", ");
                        index ++;
                    }

                    method.addStatement("$N." + exec.getSimpleName() + "(" + results.toString() + ")",
                            ((TypeElement)e.getEnclosingElement()).getQualifiedName().toString());
                }

                if(!started){
                    writeSwitch.beginControlFlow("if(id == "+id+")");
                }else{
                    writeSwitch.nextControlFlow("else if(id == "+id+")");
                }
                started = true;

                method.addStatement("$1N packet = $2N.obtain($1N.class)", "io.anuke.mindustry.net.Packets.InvokePacket",
                        "com.badlogic.gdx.utils.Pools");
                method.addStatement("packet.writeBuffer = TEMP_BUFFER");
                method.addStatement("TEMP_BUFFER.position(0)");

                ArrayList<VariableElement> parameters = new ArrayList<>(exec.getParameters());
                if(client){
                    parameters.remove(0);
                }

                for(VariableElement var : parameters){
                    String varName = var.getSimpleName().toString();
                    String typeName = var.asType().toString();
                    String bufferName = "TEMP_BUFFER";
                    String simpleTypeName = typeName.contains(".") ? typeName.substring(1 + typeName.lastIndexOf('.')) : typeName;
                    String capName = simpleTypeName.equals("byte") ? "" : Character.toUpperCase(simpleTypeName.charAt(0)) + simpleTypeName.substring(1);

                    boolean isEnum = typeUtils.directSupertypes(var.asType()).size() > 0
                            && typeUtils.asElement(typeUtils.directSupertypes(var.asType()).get(0)).getSimpleName().equals("java.lang.Enum");

                    if(isEnum) {
                        method.addStatement(bufferName + ".putShort((short)" + varName + ".ordinal())");
                    }else if(isPrimitive(typeName)) {
                        if(simpleTypeName.equals("boolean")){
                            method.addStatement(bufferName + ".put(" + varName + " ? (byte)1 : 0)");
                        }else{
                            method.addStatement(bufferName + ".put" +
                                    capName + "(" + varName + ")");
                        }
                    }else if(writeMap.get(simpleTypeName) != null){
                        String[] values = writeMap.get(simpleTypeName)[0];
                        for(String str : values){
                            method.addStatement(str.replaceAll("rbuffer", bufferName)
                                    .replaceAll("rvalue", varName));
                        }
                    }else{
                        messager.printMessage(Kind.ERROR, "No method for writing type: " + typeName, var);
                    }

                    String writeBufferName = "buffer";

                    if(isEnum) {
                        writeSwitch.addStatement(typeName + " " + varName + " = " + typeName + ".values()["+writeBufferName +".getShort()]");
                    }else if(isPrimitive(typeName)) {
                        if(simpleTypeName.equals("boolean")){
                            writeSwitch.addStatement("boolean " + varName + " = " + writeBufferName + ".get() == 1");
                        }else{
                            writeSwitch.addStatement(typeName + " " + varName + " = " + writeBufferName + ".get" + capName + "()");
                        }
                    }else if(writeMap.get(simpleTypeName) != null){
                        String[] values = writeMap.get(simpleTypeName)[1];
                        for(String str : values){
                            writeSwitch.addStatement(str.replaceAll("rbuffer", writeBufferName)
                                    .replaceAll("rvalue", varName)
                                    .replaceAll("rtype", simpleTypeName));
                        }
                    }else{
                        messager.printMessage(Kind.ERROR, "No method for writing type: " + typeName, var);
                    }
                }
                method.addStatement("packet.writeLength = TEMP_BUFFER.position()");
                method.addStatement("io.anuke.mindustry.net.Net.send(packet, "+
                        (unreliable ? "io.anuke.mindustry.net.Net.SendMode.udp" : "io.anuke.mindustry.net.Net.SendMode.tcp")+")");

                classBuilder.addMethod(method.build());

                int index = 0;
                StringBuilder results = new StringBuilder();

                for(VariableElement writevar : exec.getParameters()){
                    results.append(writevar.getSimpleName());
                    if(index != exec.getParameters().size() - 1) results.append(", ");
                    index ++;
                }

                writeSwitch.addStatement("com.badlogic.gdx.Gdx.app.postRunnable(() -> $N." + exec.getSimpleName() + "(" + results.toString() + "))",
                        ((TypeElement)e.getEnclosingElement()).getQualifiedName().toString());

                id ++;
            }

            if(started){
                writeSwitch.endControlFlow();
            }

            readMethod.addCode(writeSwitch.build());
            classBuilder.addMethod(readMethod.build());

            TypeSpec spec = classBuilder.build();

            JavaFile.builder(packageName, spec).build().writeTo(filer);


        }catch (Exception e){
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }



}