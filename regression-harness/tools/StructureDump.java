import java.io.Writer;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Version-agnostic plugin-structure dump tool for the compatibility regression harness.
 *
 * <p>Compiled with NO compile-time dependency on the structure libraries: every interaction with
 * {@code structure-intellij} goes through reflection, so the same class file can be executed
 * against arbitrary old and new builds of the library placed on the runtime classpath.
 *
 * <p>Emits a normalized, deterministic JSON dump of the {@code PluginCreationResult}:
 * absolute paths are replaced with placeholders, unordered collections are sorted, and
 * non-deterministic data (telemetry, timings) is excluded. Accessors missing in the loaded
 * library version yield the sentinel {@code "<UNSUPPORTED>"} so the harness can distinguish
 * "API not present in this build" from "property is null".
 *
 * <p>Usage: {@code java StructureDump <pluginPath> <outFile> <extractDir>}
 */
public final class StructureDump {

  private static final String UNSUPPORTED = "<UNSUPPORTED>";

  private final List<String[]> pathReplacements = new ArrayList<>();

  public static void main(String[] args) throws Exception {
    if (args.length != 3) {
      System.err.println("usage: StructureDump <pluginPath> <outFile> <extractDir>");
      System.exit(2);
    }
    Path pluginPath = Paths.get(args[0]).toAbsolutePath();
    Path outFile = Paths.get(args[1]);
    Path extractDir = Paths.get(args[2]).toAbsolutePath();
    Files.createDirectories(extractDir);

    StructureDump dump = new StructureDump();
    dump.addPathReplacement(pluginPath.getParent().toString(), "<FIXTURE_DIR>");
    dump.addPathReplacement(extractDir.toString(), "<EXTRACT_DIR>");
    dump.addPathReplacement(System.getProperty("java.io.tmpdir", "/tmp"), "<TMP>");

    Map<String, Object> result = dump.run(pluginPath, extractDir);
    StringBuilder sb = new StringBuilder();
    writeJson(sb, result, 0);
    sb.append('\n');
    Files.createDirectories(outFile.toAbsolutePath().getParent());
    try (Writer w = Files.newBufferedWriter(outFile, StandardCharsets.UTF_8)) {
      w.write(sb.toString());
    }
  }

  private void addPathReplacement(String from, String to) {
    if (from != null && !from.isEmpty()) {
      pathReplacements.add(new String[]{from, to});
    }
  }

  private Map<String, Object> run(Path pluginPath, Path extractDir) {
    Map<String, Object> root = new TreeMap<>();
    root.put("input", pluginPath.getFileName().toString());
    Object creationResult;
    try {
      Object manager = createManager(extractDir);
      Method createPlugin = manager.getClass().getMethod("createPlugin", Path.class);
      creationResult = createPlugin.invoke(manager, pluginPath);
    } catch (Throwable t) {
      Throwable cause = unwrap(t);
      root.put("outcome", "crash");
      root.put("exception", cause.getClass().getName());
      root.put("exceptionMessage", normalize(String.valueOf(cause.getMessage())));
      return root;
    }

    String kind = creationResult.getClass().getSimpleName();
    if (kind.startsWith("PluginCreationSuccess")) {
      root.put("outcome", "success");
      root.put("warnings", problems(call(creationResult, "getWarnings")));
      root.put("unacceptableWarnings", problems(call(creationResult, "getUnacceptableWarnings")));
      root.put("plugin", pluginInfo(call(creationResult, "getPlugin")));
    } else if (kind.startsWith("PluginCreationFail")) {
      root.put("outcome", "fail");
      root.put("problems", problems(call(creationResult, "getErrorsAndWarnings")));
    } else {
      root.put("outcome", "unknown:" + kind);
    }
    return root;
  }

  private Object createManager(Path extractDir) throws Exception {
    Class<?> managerClass = Class.forName("com.jetbrains.plugin.structure.intellij.plugin.IdePluginManager");
    try {
      return managerClass.getMethod("createManager", Path.class).invoke(null, extractDir);
    } catch (NoSuchMethodException e) {
      try {
        return managerClass.getMethod("createManager", java.io.File.class).invoke(null, extractDir.toFile());
      } catch (NoSuchMethodException e2) {
        return managerClass.getMethod("createManager").invoke(null);
      }
    }
  }

  private Map<String, Object> pluginInfo(Object plugin) {
    Map<String, Object> info = new TreeMap<>();
    if (plugin == null) {
      return info;
    }
    info.put("pluginId", str(call(plugin, "getPluginId")));
    info.put("pluginName", str(call(plugin, "getPluginName")));
    info.put("pluginVersion", str(call(plugin, "getPluginVersion")));
    info.put("vendor", str(call(plugin, "getVendor")));
    info.put("vendorEmail", str(call(plugin, "getVendorEmail")));
    info.put("vendorUrl", str(call(plugin, "getVendorUrl")));
    info.put("url", str(call(plugin, "getUrl")));
    info.put("sinceBuild", str(call(plugin, "getSinceBuild")));
    info.put("untilBuild", str(call(plugin, "getUntilBuild")));
    info.put("useIdeClassLoader", str(call(plugin, "getUseIdeClassLoader")));
    info.put("hasPackagePrefix", str(call(plugin, "getHasPackagePrefix")));
    info.put("isImplementationDetail", str(call(plugin, "isImplementationDetail")));
    info.put("hasDotNetPart", str(call(plugin, "getHasDotNetPart")));
    info.put("kotlinPluginMode", str(call(plugin, "getKotlinPluginMode")));

    info.put("dependencies", dependencies(call(plugin, "getDependencies")));
    info.put("definedModules", stringList(call(plugin, "getDefinedModules")));
    info.put("pluginAliases", stringList(call(plugin, "getPluginAliases")));
    info.put("incompatibleWith", stringList(call(plugin, "getIncompatibleWith")));
    info.put("contentModules", namedList(call(plugin, "getContentModules")));
    info.put("moduleDescriptors", namedList(call(plugin, "getModulesDescriptors")));
    info.put("optionalDescriptors", optionalDescriptors(call(plugin, "getOptionalDescriptors")));
    info.put("extensions", extensionCounts(call(plugin, "getExtensions")));
    info.put("declaredThemes", namedList(call(plugin, "getDeclaredThemes")));
    info.put("productDescriptor", productDescriptor(call(plugin, "getProductDescriptor")));
    return info;
  }

  private Object dependencies(Object deps) {
    if (deps == UNSUPPORTED_MARKER) {
      return UNSUPPORTED;
    }
    List<String> out = new ArrayList<>();
    if (deps instanceof Collection<?>) {
      for (Object dep : (Collection<?>) deps) {
        String id = str(call(dep, "getId"));
        String optional = str(call(dep, "isOptional", "getIsOptional"));
        String module = str(call(dep, "isModule", "getIsModule"));
        out.add(id + " [optional=" + optional + ", module=" + module + ", kind=" + dep.getClass().getSimpleName() + "]");
      }
    }
    out.sort(Comparator.naturalOrder());
    return out;
  }

  private Object optionalDescriptors(Object descriptors) {
    if (descriptors == UNSUPPORTED_MARKER) {
      return UNSUPPORTED;
    }
    List<String> out = new ArrayList<>();
    if (descriptors instanceof Collection<?>) {
      for (Object d : (Collection<?>) descriptors) {
        Object dependency = call(d, "getDependency");
        String depId = dependency == UNSUPPORTED_MARKER ? UNSUPPORTED : str(call(dependency, "getId"));
        String configFile = str(call(d, "getConfigurationFilePath"));
        out.add(depId + " -> " + configFile);
      }
    }
    out.sort(Comparator.naturalOrder());
    return out;
  }

  private Object extensionCounts(Object extensions) {
    if (extensions == UNSUPPORTED_MARKER) {
      return UNSUPPORTED;
    }
    Map<String, Object> out = new TreeMap<>();
    if (extensions instanceof Map<?, ?>) {
      for (Map.Entry<?, ?> e : ((Map<?, ?>) extensions).entrySet()) {
        int count = e.getValue() instanceof Collection<?> ? ((Collection<?>) e.getValue()).size() : 1;
        out.put(String.valueOf(e.getKey()), count);
      }
    }
    return out;
  }

  private Object productDescriptor(Object pd) {
    if (pd == UNSUPPORTED_MARKER) {
      return UNSUPPORTED;
    }
    if (pd == null) {
      return null;
    }
    Map<String, Object> out = new TreeMap<>();
    out.put("code", str(call(pd, "getCode")));
    out.put("releaseDate", str(call(pd, "getReleaseDate")));
    out.put("releaseVersion", str(call(pd, "getReleaseVersion")));
    out.put("eap", str(call(pd, "getEap", "isEap", "getIsEap")));
    return out;
  }

  private Object namedList(Object items) {
    if (items == UNSUPPORTED_MARKER) {
      return UNSUPPORTED;
    }
    List<String> out = new ArrayList<>();
    if (items instanceof Collection<?>) {
      for (Object item : (Collection<?>) items) {
        Object name = call(item, "getName");
        out.add(name == UNSUPPORTED_MARKER ? normalize(String.valueOf(item)) : str(name));
      }
    }
    out.sort(Comparator.naturalOrder());
    return out;
  }

  private Object stringList(Object items) {
    if (items == UNSUPPORTED_MARKER) {
      return UNSUPPORTED;
    }
    List<String> out = new ArrayList<>();
    if (items instanceof Collection<?>) {
      for (Object item : (Collection<?>) items) {
        out.add(str(item));
      }
    }
    out.sort(Comparator.naturalOrder());
    return out;
  }

  private Object problems(Object problems) {
    if (problems == UNSUPPORTED_MARKER) {
      return UNSUPPORTED;
    }
    List<Map<String, Object>> out = new ArrayList<>();
    if (problems instanceof Collection<?>) {
      for (Object p : (Collection<?>) problems) {
        Map<String, Object> entry = new TreeMap<>();
        entry.put("type", p.getClass().getSimpleName());
        entry.put("level", str(call(p, "getLevel")));
        entry.put("message", str(call(p, "getMessage")));
        out.add(entry);
      }
    }
    out.sort(Comparator.comparing(m -> m.get("type") + "|" + m.get("message")));
    return out;
  }

  /** Marker distinguishing "no such accessor in this library version" from a null property value. */
  private static final Object UNSUPPORTED_MARKER = new Object();

  private Object call(Object target, String... methodNames) {
    if (target == null || target == UNSUPPORTED_MARKER) {
      return UNSUPPORTED_MARKER;
    }
    for (String name : methodNames) {
      Method method;
      try {
        method = target.getClass().getMethod(name);
      } catch (NoSuchMethodException e) {
        continue;
      }
      try {
        return method.invoke(target);
      } catch (IllegalAccessException | InvocationTargetException e) {
        Throwable cause = unwrap(e);
        return "<ACCESS_ERROR:" + cause.getClass().getSimpleName() + ":" + normalize(String.valueOf(cause.getMessage())) + ">";
      }
    }
    return UNSUPPORTED_MARKER;
  }

  private String str(Object value) {
    if (value == UNSUPPORTED_MARKER) {
      return UNSUPPORTED;
    }
    if (value == null) {
      return null;
    }
    return normalize(String.valueOf(value));
  }

  private String normalize(String s) {
    if (s == null) {
      return null;
    }
    String out = s;
    for (String[] replacement : pathReplacements) {
      out = out.replace(replacement[0], replacement[1]);
    }
    // collapse extraction sub-directories with random names, e.g. <EXTRACT_DIR>/abc123.../
    out = out.replaceAll("<EXTRACT_DIR>/[\\w.-]+", "<EXTRACT_DIR>/<RANDOM>");
    out = out.replaceAll("<TMP>/[\\w.-]+", "<TMP>/<RANDOM>");
    return out;
  }

  private static Throwable unwrap(Throwable t) {
    while (t instanceof InvocationTargetException && t.getCause() != null) {
      t = t.getCause();
    }
    return t;
  }

  private static void writeJson(StringBuilder sb, Object value, int indent) {
    if (value == null) {
      sb.append("null");
    } else if (value instanceof Map<?, ?>) {
      Map<?, ?> map = (Map<?, ?>) value;
      if (map.isEmpty()) {
        sb.append("{}");
        return;
      }
      sb.append("{\n");
      int i = 0;
      for (Map.Entry<?, ?> e : map.entrySet()) {
        pad(sb, indent + 1);
        writeString(sb, String.valueOf(e.getKey()));
        sb.append(": ");
        writeJson(sb, e.getValue(), indent + 1);
        if (++i < map.size()) {
          sb.append(',');
        }
        sb.append('\n');
      }
      pad(sb, indent);
      sb.append('}');
    } else if (value instanceof Collection<?>) {
      Collection<?> list = (Collection<?>) value;
      if (list.isEmpty()) {
        sb.append("[]");
        return;
      }
      sb.append("[\n");
      int i = 0;
      for (Object item : list) {
        pad(sb, indent + 1);
        writeJson(sb, item, indent + 1);
        if (++i < list.size()) {
          sb.append(',');
        }
        sb.append('\n');
      }
      pad(sb, indent);
      sb.append(']');
    } else if (value instanceof Number || value instanceof Boolean) {
      sb.append(value);
    } else {
      writeString(sb, String.valueOf(value));
    }
  }

  private static void pad(StringBuilder sb, int indent) {
    for (int i = 0; i < indent; i++) {
      sb.append("  ");
    }
  }

  private static void writeString(StringBuilder sb, String s) {
    sb.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"': sb.append("\\\""); break;
        case '\\': sb.append("\\\\"); break;
        case '\n': sb.append("\\n"); break;
        case '\r': sb.append("\\r"); break;
        case '\t': sb.append("\\t"); break;
        default:
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
      }
    }
    sb.append('"');
  }

  private StructureDump() {
  }
}
