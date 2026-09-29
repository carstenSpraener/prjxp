# cgV19 CodeTarget Funktionsweise

**Session ID:** ses_f1853bd1fffeYt3ZAVPb6G7biD
**Created:** 9/28/2026, 1:00:21 PM
**Updated:** 9/28/2026, 1:00:33 PM

---

## User

Erkläre mir, wie im Projekt cgV19 das CodeTarget funktioniert. Den Sourcecode findest Du unter /Users/casi/Projekte/Java/cgV19/core

---

## Assistant

_Thinking:_

The user is asking in German: "Please explain how CodeTarget works in the cgV19 project. The source code is at /Users/casi/Projekte/Java/cgV19/core."

Let's explore that directory. I should use the vector search tool mentioned in AGENTS.md, but... wait, AGENTS.md is for the prjxp project. The cgV19 project is a different one located at /Users/casi/Projekte/Java/cgV19/core. The vector search tool `prjxp_vectorSearch` is probably for the prjxp project, not cgV19. So I should use grep/read/glob against the cgV19 directory.

Let's start by exploring the directory structure first.






**Tool: shell**

**Input:**
```json
{
  "command": "ls /Users/casi/Projekte/Java/cgV19/core && echo \"---\" && find /Users/casi/Projekte/Java/cgV19/core -iname \"*codetarget*\" | head -50"
}
```

build
build.gradle
cgv19-aicb
cgv19-annotationprocessor
cgv19-chunks.jsonl
cgv19-cli
cgv19-core
cgv19-gradle
cgv19-javapoet
cgv19-mcp
cgv19-metacartridge
cgv19-oom
cgv19-pojo
doc
gradle
gradle.properties
gradlew
gradlew.bat
prjxp.yaml
README.md
settings.gradle
---
/Users/casi/Projekte/Java/cgV19/core/cgv19-core/doc/CodeTargetTemplates
/Users/casi/Projekte/Java/cgV19/core/cgv19-core/doc/todos/CodeTargetImprovements.md
/Users/casi/Projekte/Java/cgV19/core/cgv19-core/build/classes/java/main/de/spraener/nxtgen/target/CodeTargetRenderer.class
/Users/casi/Projekte/Java/cgV19/core/cgv19-core/build/classes/java/main/de/spraener/nxtgen/target/CodeTargetContext.class
/Users/casi/Projekte/Java/cgV19/core/cgv19-core/build/classes/java/main/de/spraener/nxtgen/target/CodeTargetToCodeConverter.class
/Users/casi/Projekte/Java/cgV19/core/cgv19-core/build/classes/java/main/de/spraener/nxtgen/target/CodeTargetCodeBlockAdapter.class
/Users/casi/Projekte/Java/cgV19/core/cgv19-core/build/classes/java/main/de/spraener/nxtgen/target/CodeTarget.class
/Users/casi/Projekte/Java/cgV19/core/cgv19-core/build/classes/groovy/main/de/spraener/nxtgen/target/dsl/CodeTargetDSL$_BuildDSL.class
/Users/casi/Projekte/Java/cgV19/core/cgv19-core/build/classes/groovy/main/de/spraener/nxtgen/target/dsl/CodeTargetDSL.class
/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/test/java/de/spraener/nxtgen/target/CodeTargetGetOrCreateTest.java
/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/test/java/de/spraener/nxtgen/target/CodeTargetTests.java
/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/test/java/de/spraener/nxtgen/target/CodeTargetModificationTest.java
/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/test/java/de/spraener/nxtgen/target/CodeTargetToCodeConverterTest.java
/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/test/java/de/spraener/nxtgen/target/CodeTargetPathLookupTest.java
/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/test/java/de/spraener/nxtgen/target/CodeTargetContextTests.java
/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/test/java/de/spraener/nxtgen/target/CodeTargetRendererIntegrationTest.java
/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/test/java/de/spraener/nxtgen/target/CodeTargetChainableDslTest.java
/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/test/java/de/spraener/nxtgen/target/CodeTargetCodeBlockAdapterTest.java
/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/test/groovy/de/spraener/nxtgen/target/CodeTargetDSLTest.groovy
/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/main/java/de/spraener/nxtgen/target/CodeTargetToCodeConverter.java
/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/main/java/de/spraener/nxtgen/target/CodeTargetCodeBlockAdapter.java
/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/main/java/de/spraener/nxtgen/target/CodeTargetRenderer.java
/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/main/java/de/spraener/nxtgen/target/CodeTargetContext.java
/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/main/java/de/spraener/nxtgen/target/CodeTarget.java
/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/main/groovy/de/spraener/nxtgen/target/dsl/CodeTargetDSL.groovy
/Users/casi/Projekte/Java/cgV19/core/cgv19-pojo/build/classes/java/main/de/spraener/nxtgen/pojo/PoJoCodeTargetCreator.class
/Users/casi/Projekte/Java/cgV19/core/cgv19-pojo/src/test/java/de/spraener/nxtgen/pojo/PoJoCodeTargetCreatorTest.java
/Users/casi/Projekte/Java/cgV19/core/cgv19-pojo/src/main/java/de/spraener/nxtgen/pojo/PoJoCodeTargetCreator.java


---

## Assistant

_Thinking:_

Let's take a look at the main source files. I'll read CodeTarget.java, CodeTargetContext.java, CodeTargetRenderer.java, CodeTargetToCodeConverter.java, CodeTargetCodeBlockAdapter.java, and the DSL. Also, I'll check the doc folder for documentation on CodeTargetTemplates.







**Tool: read**

**Input:**
```json
{
  "path": "/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/main/java/de/spraener/nxtgen/target/CodeTarget.java"
}
```

Read file /Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/main/java/de/spraener/nxtgen/target/CodeTarget.java, lines 1-349
1: package de.spraener.nxtgen.target;
2:
3: import de.spraener.nxtgen.model.ModelElement;
4: import de.spraener.nxtgen.target.dsl.ForAspectDSL;
5: import de.spraener.nxtgen.target.java.JavaSections;
6:
7: import groovy.lang.Binding;
8: import groovy.lang.Closure;
9: import groovy.lang.GroovyShell;
10:
11: import java.io.BufferedReader;
12: import java.io.IOException;
13: import java.io.InputStream;
14: import java.io.InputStreamReader;
15: import java.net.MalformedURLException;
16: import java.net.URL;
17: import java.util.AbstractMap;
18: import java.util.ArrayList;
19: import java.util.Collection;
20: import java.util.Map;
21: import java.util.LinkedHashMap;
22: import java.util.Map;
23: import java.util.function.Consumer;
24: import java.util.function.Supplier;
25:
26: /**
27:  * <strong>Responsibility</strong>
28:  * <p>
29:  * A CodeTarget is an ordered collection of CodeSections. It can take each
30:  * CodeSection with a key. Each CodeSection can be retrieved by this key for
31:  * later modifications. A CodeTarget can only hold one CodeSection per key.
32:  * Replacing is not possible and will rise a IllegalArgumentException.
33:  * </p>
34:  * <p>
35:  * It also delivers a collection of all added section in the
36:  * order of insertion.
37:  * </p>
38:  */
39: public class CodeTarget {
40:     private Map<Object, CodeSection> mySectionMap = new LinkedHashMap<>();
41:     private CodeTargetRenderer renderer = null;
42:     private ModelElement defaultModelElement = null;
43:
44:     /**
45:      * Add a CodeSection under the given Key to the CodeTarget. A former
46:      * added CodeSection under that key will be replaced.
47:      *
48:      * @param key      A key to reference a CodeSection for later modification
49:      * @param aSection the CodeSection to be inserted.
50:      */
51:     public void addCodeSection(Object key, CodeSection aSection) {
52:         if (this.mySectionMap.get(key) != null) {
53:             throw new IllegalArgumentException("CodeSection with key " + key + " already added.");
54:         }
55:         aSection.setId(key);
56:         this.mySectionMap.put(key, aSection);
57:     }
58:
59:     /**
60:      * Add a CodeSection at a specific position in the ordered list.
61:      */
62:     public void addCodeSectionAt(Object key, CodeSection aSection, int index) {
63:         if (this.mySectionMap.get(key) != null) {
64:             throw new IllegalArgumentException("CodeSection with key " + key + " already added.");
65:         }
66:         aSection.setId(key);
67:         // Convert LinkedHashMap to list, insert at index, rebuild map
68:         java.util.List<java.util.Map.Entry<Object, CodeSection>> entries = new ArrayList<>(mySectionMap.entrySet());
69:         entries.add(index, new AbstractMap.SimpleEntry<>(key, aSection));
70:         LinkedHashMap<Object, CodeSection> newMap = new LinkedHashMap<>();
71:         for (Map.Entry<Object, CodeSection> entry : entries) {
72:             newMap.put(entry.getKey(), entry.getValue());
73:         }
74:         this.mySectionMap = newMap;
75:     }
76:
77:     /**
78:      * Remove a CodeSection by key. Returns the removed section or null.
79:      */
80:     public CodeSection removeCodeSection(Object key) {
81:         return mySectionMap.remove(key);
82:     }
83:
84:     /**
85:      * A wither for the addCodeSection-method to provide a fluent api.
86:      *
87:      * @param key     A key to reference a CodeSection for later modification.
88:      * @param section The CodeSection to be inserted.
89:      * @return The CodeTarget itself.
90:      */
91:     public CodeTarget withCodeSection(Object key, CodeSection section) {
92:         addCodeSection(key, section);
93:         return this;
94:     }
95:
96:     /**
97:      * Retrieve the CodeSection that was added with the specified key or null.
98:      * <p>
99:      * String keys may use a Unix-style path syntax separated by '/': the first segment
100:      * is the top-level section key, each following segment addresses a child scope via
101:      * {@link CodeSection#getScope(String)} (e.g. "METHODS/IN_OPERATION"). If the first
102:      * segment does not match a key exactly, it is matched against the section ids — so
103:      * sections added under enum keys (e.g. {@code JavaSections.METHODS}) are reachable
104:      * via their name ("METHODS/..."). If any segment is missing, null is returned.
105:      *
106:      * @param key The key for the CodeSection requested
107:      * @return an Optional of the CodeSection. This can be empty if no CodeSection with that key is present.
108:      */
109:     public CodeSection getSection(Object key) {
110:         if (key instanceof String path && path.contains("/")) {
111:             String[] segments = normalizePath(path).split("/", -1);
112:             CodeSection section = mySectionMap.get(segments[0]);
113:             if (section == null) {
114:                 for (CodeSection candidate : mySectionMap.values()) {
115:                     if (segments[0].equals(candidate.getId())) {
116:                         section = candidate;
117:                         break;
118:                     }
119:                 }
120:             }
121:             for (int i = 1; section != null && i < segments.length; i++) {
122:                 section = section.getScope(segments[i]);
123:             }
124:             return section;
125:         }
126:         return mySectionMap.get(key);
127:     }
128:
129:     /**
130:      * Returns the section at the given path, creating it via the supplier if it does not exist yet.
131:      * <p>
132:      * The path is '/'-separated; a leading '/' is tolerated. For a single segment the section
133:      * is looked up top-level (exact key first, then section id) and created at the end of the
134:      * table of contents if absent. For multi-segment paths all segments except the last must
135:      * resolve via {@link #getSection(Object)}; only the last segment is created, via
136:      * {@link CodeSection#getOrCreateScope(String, java.util.function.Supplier)} on the resolved
137:      * parent. The supplier is invoked at most once (deterministic caching).
138:      * </p>
139:      *
140:      * @param path the '/'-separated section path, e.g. "OPERATIONS/myOperation"
141:      * @param supplier creates the section if it does not exist yet; invoked at most once
142:      * @return the section at the given path (existing or newly created)
143:      * @throws IllegalArgumentException if a multi-segment parent path does not resolve to a section
144:      */
145:     public synchronized CodeSection getOrCreate(String path, Supplier<CodeSection> supplier) {
146:         String normalized = normalizePath(path);
147:         int slash = normalized.lastIndexOf('/');
148:         if (slash < 0) {
149:             CodeSection existing = mySectionMap.get(normalized);
150:             if (existing == null) {
151:                 for (CodeSection candidate : mySectionMap.values()) {
152:                     if (normalized.equals(candidate.getId())) {
153:                         existing = candidate;
154:                         break;
155:                     }
156:                 }
157:             }
158:             if (existing != null) {
159:                 return existing;
160:             }
161:             CodeSection section = supplier.get();
162:             addCodeSection(normalized, section);
163:             return section;
164:         }
165:         String parentPath = normalized.substring(0, slash);
166:         CodeSection parent = getSection(parentPath);
167:         if (parent == null) {
168:             throw new IllegalArgumentException("Cannot create section at path '" + path + "': parent path '" + parentPath + "' does not resolve to a section");
169:         }
170:         String name = normalized.substring(slash + 1);
171:         return parent.getOrCreateScope(name, supplier);
172:     }
173:
174:     private static String normalizePath(String path) {
175:         return path.startsWith("/") ? path.substring(1) : path;
176:     }
177:
178:     /**
179:      * Delivers all added CodeSections in the order of Insertion.
180:      *
181:      * @return
182:      */
183:     public Collection<CodeSection> getSectionsOrdered() {
184:         return this.mySectionMap.values();
185:     }
186:
187:     /**
188:      * Opens a new CodeTargetContext and calls all consumers on this CodeTarget, so they are working in that
189:      * given CodeTargetContext.
190:      *
191:      * @param aspect    An aspect the consumer working on or null.
192:      * @param me        A ModelElement the consumers working on or null.
193:      * @param consumers a list of consumers to do some work on this CodeTarget.
194:      * @return CodeTarget itself for queuing.
195:      */
196:     public CodeTarget forAspect(Object aspect, ModelElement me, Consumer<CodeTarget>... consumers) {
197:         try (var ctxt = new CodeTargetContext(aspect, me)) {
198:             if (consumers != null) {
199:                 for (Consumer<CodeTarget> consumer : consumers) {
200:                     consumer.accept(this);
201:                 }
202:             }
203:         }
204:         return this;
205:     }
206:
207:     /**
208:      * DSL variant: opens a CodeTargetContext and executes a Groovy closure with ForAspectDSL as delegate.
209:      * Snippets added via the DSL automatically get the aspect and model element from context.
210:      */
211:     public CodeTarget forAspect(Object aspect, ModelElement me, Closure closure) {
212:         try (var ctxt = new CodeTargetContext(aspect, me)) {
213:             ForAspectDSL dsl = new ForAspectDSL(this);
214:             closure.setDelegate(dsl);
215:             closure.setResolveStrategy(Closure.DELEGATE_FIRST);
216:             closure.call();
217:         }
218:         return this;
219:     }
220:
221:     /**
222:      * 2-arg DSL variant: uses defaultModelElement (set via setDefaultModelElement).
223:      * Enables fluent chaining in Groovy scripts:
224:      * <pre>
225:      * ct.setDefaultModelElement(mClass)
226:      *    .forAspect('logging') { to 'imports', "import java.util.logging.Logger;" }
227:      * </pre>
228:      */
229:     public CodeTarget forAspect(Object aspectName, Closure closure) {
230:         return forAspect(aspectName, this.defaultModelElement, closure);
231:     }
232:
233:     /**
234:      * Set the default model element for 2-arg forAspect calls. Chainable.
235:      */
236:     public CodeTarget setDefaultModelElement(ModelElement me) {
237:         this.defaultModelElement = me;
238:         return this;
239:     }
240:
241:     /**
242:      * Get the default model element.
243:      */
244:     public ModelElement getDefaultModelElement() {
245:         return this.defaultModelElement;
246:     }
247:
248:     /**
249:      * Load and execute an external Groovy script with binding:
250:      * - ct = this CodeTarget
251:      * - mClass = defaultModelElement
252:      * - modelElement = defaultModelElement
253:      * Chainable for fluent usage.
254:      */
255:     public CodeTarget evaluate(String scriptPath) {
256:         String script = loadScript(scriptPath);
257:         if (script == null) return this;
258:
259:         Binding b = new Binding();
260:         b.setVariable("ct", this);
261:         b.setVariable("mClass", this.defaultModelElement);
262:         b.setVariable("modelElement", this.defaultModelElement);
263:
264:         GroovyShell shell = new GroovyShell(b);
265:         try {
266:             shell.evaluate(script, scriptPath);
267:         } catch (Exception e) {
268:             throw new RuntimeException("Error evaluating script " + scriptPath + ": " + e.getMessage(), e);
269:         }
270:         return this;
271:     }
272:
273:     /**
274:      * Load a Groovy script from classpath or URL.
275:      */
276:     private String loadScript(String scriptPath) {
277:         try {
278:             InputStreamReader reader;
279:             if (scriptPath.startsWith("http")) {
280:                 reader = new InputStreamReader(new URL(scriptPath).openStream());
281:             } else {
282:                 // Try classpath resource (from package context)
283:                 InputStream is = CodeTarget.class.getResourceAsStream(scriptPath);
284:                 
285:                 // Try classpath root (for test resources)
286:                 if (is == null) {
287:                     is = Thread.currentThread().getContextClassLoader().getResourceAsStream(scriptPath.substring(1));
288:                 }
289:                 
290:                 // Try as file path
291:                 if (is == null) {
292:                     java.io.File f = new java.io.File(scriptPath);
293:                     if (f.exists()) {
294:                         is = new java.io.FileInputStream(f);
295:                     } else {
296:                         return null;
297:                     }
298:                 }
299:                 reader = new InputStreamReader(is);
300:             }
301:
302:             BufferedReader br = new BufferedReader(reader);
303:             StringBuilder sb = new StringBuilder();
304:             String line;
305:             while ((line = br.readLine()) != null) {
306:                 sb.append(line).append('\n');
307:             }
308:             return sb.toString();
309:         } catch (IOException e) {
310:             throw new RuntimeException("Could not load script " + scriptPath, e);
311:         }
312:     }
313:
314:     public void setRenderer(CodeTargetRenderer renderer) {
315:         this.renderer = renderer;
316:     }
317:
318:     public CodeTargetRenderer getRenderer() {
319:         return renderer;
320:     }
321:
322:     public CodeTarget append(Object sectionKey, String code) {
323:         if( CodeTargetContext.getActiveContext()!=null ) {
324:             Object aspect = CodeTargetContext.getActiveContext().getAspect();
325:             getSection(sectionKey).add(new SingleLineSnippet(aspect, code));
326:         } else {
327:             getSection(sectionKey).add(new SingleLineSnippet(code));
328:         }
329:         return this;
330:     }
331:
332:     public void beforeLastSnippetOfAspect(Object sectionKey, Object aspectRef, String code) {
333:         CodeSection section = getSection(sectionKey);
334:         CodeSnippet snippet = section.getLastSnippetForAspect(aspectRef).get();
335:         if( CodeTargetContext.getActiveContext()!=null ) {
336:             Object aspect = CodeTargetContext.getActiveContext().getAspect();
337:             section.insertBefore(snippet, new SingleLineSnippet(aspect, code));
338:         } else {
339:             section.insertBefore(snippet, new SingleLineSnippet(code));
340:         }
341:     }
342:
343:     /**
344:      * Add a snippet to the named section. Used by ForAspectDSL.
345:      */
346:     public void addSnippetToSection(Object sectionKey, String content) {
347:         append(sectionKey, content);
348:     }
349: }

**Tool: read**

**Input:**
```json
{
  "path": "/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/main/java/de/spraener/nxtgen/target/CodeTargetContext.java"
}
```

Read file /Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/main/java/de/spraener/nxtgen/target/CodeTargetContext.java, lines 1-87
1: package de.spraener.nxtgen.target;
2:
3: import de.spraener.nxtgen.model.ModelElement;
4:
5: import java.util.Stack;
6:
7: /**
8:  * <strong>Responsibility</strong>
9:  * The CodeTargetContext manages a Stack in a ThreadLocal variable to give access to the current
10:  * Aspect and ModelElement. With the use of the CodeTargetContext a Snippet can retrieve this
11:  * information automatically from the ThreadLocal. This makes the writing of ClassTarget-Creators
12:  * much more readable.
13:  * <p>
14:  * The CodeTargetContext is a Autocloseable and it is intended to be used in a try()-Header. Nesting
15:  * is possible. A nested ClassTargetContext will push its information on the stack and pop it
16:  * on close. The last close will empty the ThreadLocal variable.
17:  * </p><p>
18:  * Access to the CodeTargetContext is possible to the static method getActiveContext
19:  *</p>
20:  */
21: public class CodeTargetContext implements AutoCloseable {
22:     private static ThreadLocal<Stack<CodeTargetContext>> activeContext = new ThreadLocal<>();
23:     private static CodeTargetContext EMPTY_CONTEXT = new CodeTargetContext(null, null);
24:
25:     private Object aspect;
26:     private ModelElement modelElement;
27:
28:     /**
29:      * Activate a new ClassTargetContext with the given Information.
30:      *
31:      * @param aspect an Aspect describing the purpose.
32:      * @param modelElement a ModelElement that the CodeTarget creator is currently working on.
33:      */
34:     public CodeTargetContext(Object aspect, ModelElement modelElement) {
35:         this.aspect = aspect;
36:         this.modelElement = modelElement;
37:         getActiveContextStack().push(this);
38:     }
39:
40:     /**
41:      * Retrieves the current active ClassTargetContext from the ThreadLocal Stack. It there is
42:      * no active ClassTargetContext it will return an empty CodeTargetContex.
43:      *
44:      * @return the currently active ClassTargetContext or null.
45:      */
46:     public static CodeTargetContext getActiveContext() {
47:         if (activeContext.get() == null || activeContext.get().isEmpty()) {
48:             return EMPTY_CONTEXT;
49:         }
50:         return getActiveContextStack().peek();
51:     }
52:
53:     private static Stack<CodeTargetContext> getActiveContextStack() {
54:         Stack<CodeTargetContext> tlStack = activeContext.get();
55:         if (tlStack == null) {
56:             tlStack = new Stack<>();
57:             activeContext.set(tlStack);
58:         }
59:         return tlStack;
60:     }
61:
62:     /**
63:      * Returns the aspect of this (currently active) CodeTargetContext.
64:      *
65:      * @return aspect or null
66:      */
67:     public Object getAspect() {
68:         return aspect;
69:     }
70:
71:     /**
72:      * Returns the ModelElement of this (currently active) CodeTargetContext
73:      *
74:      * @return ModelElement or null
75:      */
76:     public ModelElement getModelElement() {
77:         return modelElement;
78:     }
79:
80:     @Override
81:     public void close() {
82:         getActiveContextStack().pop();
83:         if (activeContext.get().isEmpty()) {
84:             activeContext.set(null);
85:         }
86:     }
87: }

---

## Assistant

_Thinking:_

Next, let's take a look at CodeSection, CodeSnippet, SingleLineSnippet, CodeTargetRenderer, CodeTargetToCodeConverter, and the DSL. I'll list out the target package directory.






**Tool: shell**

**Input:**
```json
{
  "command": "ls /Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/main/java/de/spraener/nxtgen/target/ /Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/main/java/de/spraener/nxtgen/target/dsl/ /Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/main/java/de/spraener/nxtgen/target/java/ 2>/dev/null; ls /Users/casi/Projekte/Java/cgV19/core/cgv19-core/doc/CodeTargetTemplates"
}
```

/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/main/java/de/spraener/nxtgen/target/:
AbstractCodeSection.java
CodeBlockSnippet.java
CodeSection.java
CodeSnippet.java
CodeSnippetRef.java
CodeTarget.java
CodeTargetCodeBlockAdapter.java
CodeTargetContext.java
CodeTargetRenderer.java
CodeTargetToCodeConverter.java
dsl
java
JavaRenderer.java
NonEmptyPrefixedListSection.java
package-info.java
SectionName.java
SectionType.java
SimpleCodeSection.java
SingleLineSnippet.java
StandardSections.java
typescript
UniqueLineSection.java

/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/main/java/de/spraener/nxtgen/target/dsl/:
ForAspectDSL.java

/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/main/java/de/spraener/nxtgen/target/java/:
JavaAspects.java
JavaImplementsCodeSection.java
JavaSections.java


---

## Assistant

_Thinking:_

The doc/CodeTargetTemplates directory seems to be empty, or the output got cut off partway through. Let me read the main files: CodeSection.java, AbstractCodeSection.java, SimpleCodeSection.java, CodeSnippet.java, SingleLineSnippet.java, CodeTargetRenderer.java, JavaRenderer.java, ForAspectDSL.java, JavaSections.java.







**Tool: read**

**Input:**
```json
{
  "path": "/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/main/java/de/spraener/nxtgen/target/CodeSection.java"
}
```

Read file /Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/main/java/de/spraener/nxtgen/target/CodeSection.java, lines 1-104
1: package de.spraener.nxtgen.target;
2:
3: import de.spraener.nxtgen.model.ModelElement;
4:
5: import java.util.Collection;
6: import java.util.Collections;
7: import java.util.List;
8: import java.util.UUID;
9: import java.util.function.Supplier;
10:
11: public interface CodeSection {
12:     String getId();
13:     void setId(Object id);
14:     List<CodeSnippetRef> getSnippetsForAspect(Object aspect);
15:     CodeSnippetRef getLastSnippetForAspect(Object aspect);
16:     CodeSnippetRef getFirstSnippetForAspect(Object aspect);
17:     List<CodeSnippetRef> getSnippetsForAspectAndModelElement(Object key, ModelElement me);
18:     CodeSnippetRef getFirstSnippetForAspectAndModelElement(Object key, ModelElement me);
19:     CodeSnippetRef getLastSnippetForAspectAndModelElement(Object key, ModelElement me);
20:
21:     CodeSection add(CodeSnippet snippet);
22:     CodeSection addFirst(CodeSnippet snippet);
23:     CodeSection add(Object aspect, String code);
24:     CodeSection add(Object aspect, ModelElement me, String code);
25:     CodeSection insertBefore(CodeSnippet snippet, CodeSnippet snippetToInsert);
26:     CodeSection insertAfter(CodeSnippet snippet, CodeSnippet snippetToInsert);
27:
28:     /**
29:      * DANGER! This replace-method will REPLACE! the snippet with the snippetToInsert. Only use this method
30:      * when you are really sure to do so. The aspect of the snippetToInsert will be overwritten with the
31:      * aspect of the replaced snippet. USE WITH CARE!
32:      *
33:      * @param snippet the snippet to be replaced and to take the aspect from
34:      * @param snippetToInsert the snippet to replace the old snippet and get the aspect from the old snippet.
35:      * @return the CodeSection with replaced snippet.
36:      */
37:     CodeSection replace(CodeSnippet snippet, CodeSnippet snippetToInsert);
38:
39:     /**
40:      * Inserts the given snippet at the end of the child scope addressed by the given path.
41:      * The path is '/'-separated and resolved relative to this section via
42:      * {@link #getScope(String)}; a leading '/' is tolerated. Every path segment must name
43:      * an existing child scope — missing scopes are not created (use
44:      * {@link #getOrCreateScope(String, java.util.function.Supplier)} for that).
45:      * The default implementation throws {@link UnsupportedOperationException}; composite
46:      * sections (see AbstractCodeSection) support it.
47:      *
48:      * @param path the '/'-separated scope path, e.g. "IN_OPERATION" or "/IN_OPERATION/DETAILS"
49:      * @param snippet the snippet to insert at the end of the addressed scope
50:      * @return the section that received the snippet (for chaining)
51:      */
52:     default CodeSection insert(String path, CodeSnippet snippet) {
53:         throw new UnsupportedOperationException("Path-based insertion is not supported by " + getClass().getSimpleName());
54:     }
55:
56:     Collection<CodeSnippet> getSnippetsOrdered();
57:
58:     /**
59:      * Returns the snippets contained directly in this section, without any child scopes.
60:      * The default implementation returns {@link #getSnippetsOrdered()}, which is correct
61:      * for flat sections without children.
62:      */
63:     default Collection<CodeSnippet> getOwnSnippets() {
64:         return getSnippetsOrdered();
65:     }
66:
67:     /**
68:      * Returns the child scope with the given name, creating it via the supplier on first
69:      * access. The supplier is invoked at most once (deterministic caching).
70:      */
71:     default CodeSection getOrCreateScope(String scopeName, Supplier<CodeSection> scopeSupplier) {
72:         throw new UnsupportedOperationException("Scopes are not supported by " + getClass().getSimpleName());
73:     }
74:
75:     /**
76:      * Returns the child scope with the given name or null if it does not exist.
77:      */
78:     default CodeSection getScope(String scopeName) {
79:         return null;
80:     }
81:
82:     /**
83:      * Returns the parent section or null if this is a root section.
84:      */
85:     default CodeSection getParent() {
86:         return null;
87:     }
88:
89:     /**
90:      * Returns the child scopes in insertion order.
91:      */
92:     default Collection<CodeSection> getChildren() {
93:         return Collections.emptyList();
94:     }
95:
96:     /**
97:      * If true, child scopes are rendered as pure logical groupings: no own indentation,
98:      * no line breaks — the section renders its full recursive snippet list as one flat block.
99:      */
100:     default boolean rendersChildrenInline() {
101:         return false;
102:     }
103:
104: }

**Tool: read**

**Input:**
```json
{
  "path": "/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/main/java/de/spraener/nxtgen/target/AbstractCodeSection.java"
}
```

Read file /Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/main/java/de/spraener/nxtgen/target/AbstractCodeSection.java, lines 1-256
1: package de.spraener.nxtgen.target;
2:
3: import de.spraener.nxtgen.model.ModelElement;
4:
5: import java.util.*;
6: import java.util.function.Supplier;
7: import java.util.stream.Collectors;
8:
9: public abstract class AbstractCodeSection implements CodeSection {
10:     /** Global ordered list of all snippets — preserves insertion and insertBefore/After order. */
11:     List<CodeSnippet> allSnippets = new ArrayList<>();
12:     private Object id = UUID.randomUUID();
13:     /** Child scopes in deterministic insertion order. All access is synchronized on this map. */
14:     private final Map<String, CodeSection> children = new LinkedHashMap<>();
15:     private CodeSection parent;
16:
17:     public void setParent(CodeSection parent) {
18:         this.parent = parent;
19:     }
20:
21:     @Override
22:     public CodeSection getParent() {
23:         return this.parent;
24:     }
25:
26:     @Override
27:     public synchronized CodeSection getOrCreateScope(String scopeName, Supplier<CodeSection> scopeSupplier) {
28:         return children.computeIfAbsent(scopeName, name -> {
29:             CodeSection child = scopeSupplier.get();
30:             child.setId(name);
31:             if (child instanceof AbstractCodeSection abs) {
32:                 abs.setParent(this);
33:             }
34:             return child;
35:         });
36:     }
37:
38:     @Override
39:     public synchronized CodeSection getScope(String scopeName) {
40:         return children.get(scopeName);
41:     }
42:
43:     @Override
44:     public synchronized Collection<CodeSection> getChildren() {
45:         return new ArrayList<>(children.values());
46:     }
47:
48:     @Override
49:     public String getId() {
50:         return this.id.toString();
51:     }
52:
53:     public void setId(Object id) {
54:         this.id = id;
55:     }
56:
57:     public AbstractCodeSection withSnippet(Object key, CodeSnippet snippet) {
58:         allSnippets.add(snippet);
59:         return this;
60:     }
61:
62:     public AbstractCodeSection withSnippet(Object key, String lineOfCode) {
63:         return withSnippet(key, new SingleLineSnippet(key, lineOfCode));
64:     }
65:
66:     @Override
67:     public AbstractCodeSection add(CodeSnippet snippet) {
68:         allSnippets.add(snippet);
69:         return this;
70:     }
71:
72:     @Override
73:     public AbstractCodeSection addFirst(CodeSnippet snippet) {
74:         if (!allSnippets.isEmpty()) {
75:             allSnippets.add(0, snippet);
76:         } else {
77:             allSnippets.add(snippet);
78:         }
79:         return this;
80:     }
81:
82:     @Override
83:     public CodeSection add(Object aspect, String code) {
84:         return add(new CodeBlockSnippet(aspect, null, code));
85:     }
86:
87:     @Override
88:     public AbstractCodeSection add(Object aspect, ModelElement me, String code) {
89:         return add(new CodeBlockSnippet(aspect, me, code));
90:     }
91:
92:     @Override
93:     public List<CodeSnippetRef> getSnippetsForAspect(Object aspect) {
94:         return allSnippets.stream()
95:                 .filter(s -> {
96:                     if (aspect == null) return true;
97:                     return aspect.equals(s.getAspect());
98:                 })
99:                 .map(s -> new CodeSnippetRef(this, s))
100:                 .collect(Collectors.toList());
101:     }
102:
103:     @Override
104:     public CodeSnippetRef getFirstSnippetForAspect(Object aspect) {
105:         return allSnippets.stream()
106:                 .filter(s -> aspect == null || aspect.equals(s.getAspect()))
107:                 .map(s -> new CodeSnippetRef(this, s))
108:                 .findFirst().orElse(null);
109:     }
110:
111:     @Override
112:     public CodeSnippetRef getLastSnippetForAspect(Object aspect) {
113:         List<CodeSnippetRef> snippetList = getSnippetsForAspect(aspect);
114:         if (snippetList == null || snippetList.isEmpty()) {
115:             return null;
116:         }
117:         return snippetList.get(snippetList.size() - 1);
118:     }
119:
120:     @Override
121:     public List<CodeSnippetRef> getSnippetsForAspectAndModelElement(Object aspect, ModelElement me) {
122:         return allSnippets.stream()
123:                 .filter(s -> s.matches(aspect, me))
124:                 .map(s -> new CodeSnippetRef(this, s))
125:                 .collect(Collectors.toList());
126:     }
127:
128:     @Override
129:     public CodeSnippetRef getFirstSnippetForAspectAndModelElement(Object aspect, ModelElement me) {
130:         return allSnippets.stream()
131:                 .filter(s -> s.matches(aspect, me))
132:                 .map(s -> new CodeSnippetRef(this, s))
133:                 .findFirst().orElse(null);
134:     }
135:
136:     @Override
137:     public CodeSnippetRef getLastSnippetForAspectAndModelElement(Object aspect, ModelElement me) {
138:         List<CodeSnippetRef> snippetList = getSnippetsForAspectAndModelElement(aspect, me);
139:         if (snippetList == null || snippetList.isEmpty()) {
140:             return null;
141:         }
142:         return snippetList.get(snippetList.size() - 1);
143:     }
144:
145:     @Override
146:     public CodeSection insertBefore(CodeSnippet snippet, CodeSnippet snippetToInsert) {
147:         int idx = allSnippets.indexOf(snippet);
148:         if (idx >= 0) {
149:             allSnippets.add(idx, snippetToInsert);
150:             return this;
151:         }
152:         CodeSection owner = findOwnerOf(snippet);
153:         if (owner != null) {
154:             return owner.insertBefore(snippet, snippetToInsert);
155:         }
156:         throw new IllegalArgumentException("Snippet " + snippet + " not part of CodeSection " + this);
157:     }
158:
159:     @Override
160:     public CodeSection insertAfter(CodeSnippet snippet, CodeSnippet snippetToInsert) {
161:         int idx = allSnippets.indexOf(snippet);
162:         if (idx >= 0) {
163:             allSnippets.add(idx + 1, snippetToInsert);
164:             return this;
165:         }
166:         CodeSection owner = findOwnerOf(snippet);
167:         if (owner != null) {
168:             return owner.insertAfter(snippet, snippetToInsert);
169:         }
170:         throw new IllegalArgumentException("Snippet " + snippet + " not part of CodeSection " + this);
171:     }
172:
173:     @Override
174:     public CodeSection replace(CodeSnippet snippet, CodeSnippet snippetToInsert) {
175:         int idx = allSnippets.indexOf(snippet);
176:         if (idx >= 0) {
177:             allSnippets.add(idx, snippetToInsert);
178:             snippetToInsert.updateAspect(snippet);
179:             allSnippets.remove(idx + 1);
180:             return this;
181:         }
182:         CodeSection owner = findOwnerOf(snippet);
183:         if (owner != null) {
184:             return owner.replace(snippet, snippetToInsert);
185:         }
186:         return this;
187:     }
188:
189:     @Override
190:     public CodeSection insert(String path, CodeSnippet snippet) {
191:         CodeSection target = resolveScopePath(path);
192:         target.add(snippet);
193:         return target;
194:     }
195:
196:     /**
197:      * Resolves a '/'-separated scope path relative to this section. A leading '/' is
198:      * tolerated. Every segment must name an existing child scope; otherwise an
199:      * IllegalArgumentException is thrown (missing scopes are not created).
200:      */
201:     private CodeSection resolveScopePath(String path) {
202:         String normalized = normalizePath(path);
203:         CodeSection current = this;
204:         for (String segment : normalized.split("/", -1)) {
205:             CodeSection next = current.getScope(segment);
206:             if (next == null) {
207:                 throw new IllegalArgumentException("Cannot resolve scope path '" + path + "': segment '" + segment + "' not found in section with id " + current.getId());
208:             }
209:             current = next;
210:         }
211:         return current;
212:     }
213:
214:     private static String normalizePath(String path) {
215:         return path.startsWith("/") ? path.substring(1) : path;
216:     }
217:
218:     /**
219:      * Finds the section in this subtree that directly contains the given snippet,
220:      * searching own snippets first and then all child scopes recursively.
221:      */
222:     private CodeSection findOwnerOf(CodeSnippet snippet) {
223:         if (allSnippets.contains(snippet)) {
224:             return this;
225:         }
226:         for (CodeSection child : getChildren()) {
227:             if (child instanceof AbstractCodeSection abs) {
228:                 CodeSection owner = abs.findOwnerOf(snippet);
229:                 if (owner != null) {
230:                     return owner;
231:                 }
232:             } else if (child.getOwnSnippets().contains(snippet)) {
233:                 return child;
234:             }
235:         }
236:         return null;
237:     }
238:
239:     /**
240:      * Depth-first, pre-order aggregation: own snippets first, then the snippets of each
241:      * child scope in insertion order (recursively).
242:      */
243:     @Override
244:     public Collection<CodeSnippet> getSnippetsOrdered() {
245:         List<CodeSnippet> result = new ArrayList<>(allSnippets);
246:         for (CodeSection child : getChildren()) {
247:             result.addAll(child.getSnippetsOrdered());
248:         }
249:         return result;
250:     }
251:
252:     @Override
253:     public Collection<CodeSnippet> getOwnSnippets() {
254:         return new ArrayList<>(allSnippets);
255:     }
256: }

---

## Assistant

_Thinking:_

Next, let's take a look at CodeSnippet, SingleLineSnippet, CodeBlockSnippet, CodeTargetRenderer, JavaRenderer, ForAspectDSL, and JavaSections.







**Tool: read**

**Input:**
```json
{
  "path": "/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/main/java/de/spraener/nxtgen/target/CodeSnippet.java"
}
```

Read file /Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/main/java/de/spraener/nxtgen/target/CodeSnippet.java, lines 1-84
1: package de.spraener.nxtgen.target;
2:
3: import de.spraener.nxtgen.model.ModelElement;
4:
5: import java.util.ArrayList;
6: import java.util.List;
7:
8: /**
9:  * <strong>Responsibility</strong>
10:  * A CodeSnippet is a atomar piece of code that can be referenced by an aspect
11:  * and an optional ModelElement. It can evaluate itself into a StringBuilder.
12:  * It always has an "aspect" this snippet is needed for, and it can have a ModelElement
13:  * for which this Snippet is also needed.
14:  */
15: public abstract class CodeSnippet {
16:     /**
17:      * The aspect this snippet implements. For example JPA-Entity
18:      */
19:     private Object aspect;
20:     /**
21:      * The reason why this snippet is there. For Example the MAttribute that needs
22:      * this snippet. With this information a CodeTarget can be modified by
23:      * searching the snippet for aspect A and ModelElement E, and then
24:      * inserting a snippet before that snippet.
25:      */
26:     private ModelElement me;
27:
28:     public CodeSnippet() {
29:         this(CodeTargetContext.getActiveContext().getAspect(), CodeTargetContext.getActiveContext().getModelElement());
30:     }
31:     /**
32:      * Create a snippet to implement the given aspect. The ModelELement is set to null.
33:      * @param aspect The aspect this snippet is needed for.
34:      */
35:     public CodeSnippet(Object aspect) {
36:         this(aspect, CodeTargetContext.getActiveContext().getModelElement());
37:     }
38:
39:     /**
40:      * Create a Snippet to implement the given aspect for the given ModelElement.
41:      * For example the snippet implements the JPA-Aspect for a MAttribute.
42:      * @param aspect The aspect this snippet is needed for.
43:      * @param me The ModelElement this snippet is needed for.
44:      */
45:     public CodeSnippet(Object aspect, ModelElement me) {
46:         this.aspect = aspect;
47:         this.me = me;
48:     }
49:
50:     public abstract void evaluate(StringBuilder sb);
51:
52:     /**
53:      * Does this snippet match the given aspect and ModelElement? If the snippet
54:      * has no ModelElement it only matches requests with ModelElement parameter is null.
55:      * A null-ModelElement on a snippet does not match any ModelElement-Parameter.
56:      *
57:      * @param aspect The Aspect to retrieve snippets for
58:      * @param me the ModelElement to retrieve snippets for or null.
59:      *
60:      * @return TRUE if the snippet matches the given parameters
61:      */
62:     public boolean matches(Object aspect, ModelElement me) {
63:         if( this.aspect!=null && this.aspect.equals(aspect) ) {
64:             if( this.me==null && me==null ) {
65:                 return true;
66:             } else {
67:                 return this.me!=null && this.me.equals(me);
68:             }
69:         }
70:         return false;
71:     }
72:
73:     public Object getAspect() {
74:         return aspect;
75:     }
76:
77:     public ModelElement getModelElement() {
78:         return this.me;
79:     }
80:
81:     void updateAspect(CodeSnippet snippet) {
82:         this.aspect = snippet.getAspect();
83:     }
84: }

**Tool: read**

**Input:**
```json
{
  "path": "/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/main/java/de/spraener/nxtgen/target/CodeTargetRenderer.java"
}
```

Read file /Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/main/java/de/spraener/nxtgen/target/CodeTargetRenderer.java, lines 1-15
1: package de.spraener.nxtgen.target;
2:
3: /**
4:  * Interface for rendering a CodeTarget into a string representation.
5:  */
6: public interface CodeTargetRenderer {
7:
8:     /**
9:      * Render the given CodeTarget into a string.
10:      *
11:      * @param codeTarget the CodeTarget to render
12:      * @return the rendered string representation
13:      */
14:     String render(CodeTarget codeTarget);
15: }

---

## Assistant

_Thinking:_

Next, let's take a look at JavaRenderer, ForAspectDSL, JavaSections, and how CodeTarget is used in the generator (e.g., PoJoCodeTargetCreator). Also, let's check CodeTargetToCodeConverter and the documentation.







**Tool: read**

**Input:**
```json
{
  "path": "/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/main/java/de/spraener/nxtgen/target/JavaRenderer.java"
}
```

Read file /Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/main/java/de/spraener/nxtgen/target/JavaRenderer.java, lines 1-61
1: package de.spraener.nxtgen.target;
2:
3: /**
4:  * Renders a CodeTarget into Java source code format.
5:  */
6: public class JavaRenderer implements CodeTargetRenderer {
7:
8:     private static final String INDENT_UNIT = "    ";
9:
10:     private boolean withMarkers = false;
11:
12:     /**
13:      * Enable section markers (comments) in the output.
14:      */
15:     public JavaRenderer withMarkers() {
16:         this.withMarkers = true;
17:         return this;
18:     }
19:
20:     @Override
21:     public String render(CodeTarget codeTarget) {
22:         StringBuilder sb = new StringBuilder();
23:         for (CodeSection section : codeTarget.getSectionsOrdered()) {
24:             renderSection(sb, section, 0);
25:         }
26:         return sb.toString();
27:     }
28:
29:     private void renderSection(StringBuilder sb, CodeSection section, int indentLevel) {
30:         String pad = INDENT_UNIT.repeat(indentLevel);
31:         if (withMarkers) {
32:             sb.append(pad).append("<<section id=").append(section.getId()).append(">>\n");
33:         }
34:
35:         if (section.rendersChildrenInline()) {
36:             for (CodeSnippet snippet : section.getSnippetsOrdered()) {
37:                 renderSnippet(sb, snippet, pad);
38:             }
39:         } else {
40:             for (CodeSnippet snippet : section.getOwnSnippets()) {
41:                 renderSnippet(sb, snippet, pad);
42:             }
43:             for (CodeSection child : section.getChildren()) {
44:                 renderSection(sb, child, indentLevel + 1);
45:             }
46:         }
47:
48:         if (withMarkers) {
49:             sb.append(pad).append("<</section:").append(section.getId()).append(">>\n");
50:         }
51:     }
52:
53:     private void renderSnippet(StringBuilder sb, CodeSnippet snippet, String pad) {
54:         StringBuilder snippetSb = new StringBuilder();
55:         snippet.evaluate(snippetSb);
56:         String line = snippetSb.toString().trim();
57:         if (!line.isEmpty()) {
58:             sb.append(pad).append(line).append("\n");
59:         }
60:     }
61: }

**Tool: read**

**Input:**
```json
{
  "path": "/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/main/java/de/spraener/nxtgen/target/dsl/ForAspectDSL.java"
}
```

Read file /Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/main/java/de/spraener/nxtgen/target/dsl/ForAspectDSL.java, lines 1-165
1: package de.spraener.nxtgen.target.dsl;
2:
3: import de.spraener.nxtgen.model.ModelElement;
4: import de.spraener.nxtgen.target.*;
5: import groovy.lang.Closure;
6:
7: import java.util.ArrayList;
8: import java.util.Collection;
9: import java.util.List;
10: import java.util.Map;
11:
12: /**
13:  * DSL helper for CodeTarget. Provides fluent methods for adding code sections via Groovy closures.
14:  */
15: public class ForAspectDSL {
16:
17:     private final CodeTarget codeTarget;
18:
19:     public ForAspectDSL(CodeTarget codeTarget) {
20:         this.codeTarget = codeTarget;
21:     }
22:
23:     /**
24:      * Add a snippet to the named section.
25:      */
26:     public void to(Object sectionKey, String content) {
27:         codeTarget.addSnippetToSection(sectionKey, content);
28:     }
29:
30:     /**
31:      * Add a snippet to the named section using a closure.
32:      */
33:     public void to(Object sectionKey, Closure closure) {
34:         String content = String.valueOf(closure.call());
35:         codeTarget.addSnippetToSection(sectionKey, content);
36:     }
37:
38:     /**
39:      * Add a snippet to the beginning of the named section. The new snippet inherits the
40:      * aspect and model element from the active {@link CodeTargetContext}.
41:      */
42:     public void first(Object sectionKey, String content) {
43:         CodeSection section = codeTarget.getSection(sectionKey);
44:         if (section == null) {
45:             throw new IllegalArgumentException("Cannot add to section " + sectionKey + ": not found");
46:         }
47:         Object aspect = CodeTargetContext.getActiveContext().getAspect();
48:         section.addFirst(new SingleLineSnippet(aspect, content));
49:     }
50:
51:     /**
52:      * Add a new SIMPLE section with the given name at the end of the table of contents.
53:      * Idempotent: if a section with that name already exists, nothing happens.
54:      */
55:     public void addSection(String name) {
56:         addSection(name, SectionType.SIMPLE);
57:     }
58:
59:     /**
60:      * Add a new section of the given type with the given name at the end of the table
61:      * of contents. Idempotent: if a section with that name already exists, nothing happens.
62:      */
63:     public void addSection(String name, SectionType type) {
64:         if (codeTarget.getSection(name) != null) {
65:             return;
66:         }
67:         codeTarget.addCodeSection(name, type.create(name));
68:     }
69:
70:     /**
71:      * Add a new section of the given type with configuration at the end of the table of
72:      * contents. Idempotent: if a section with that name already exists, nothing happens.
73:      */
74:     public void addSection(String name, SectionType type, Map<String, Object> config) {
75:         if (codeTarget.getSection(name) != null) {
76:             return;
77:         }
78:         codeTarget.addCodeSection(name, type.create(name, config));
79:     }
80:
81:     /**
82:      * Add a new SIMPLE section with the given name directly after the existing section
83:      * identified by {@code afterKey}. Idempotent: if a section with that name already
84:      * exists, nothing happens.
85:      */
86:     public void addSection(String name, String afterKey) {
87:         if (codeTarget.getSection(name) != null) {
88:             return;
89:         }
90:         int index = indexOf(afterKey) + 1;
91:         codeTarget.addCodeSectionAt(name, SectionType.SIMPLE.create(name), index);
92:     }
93:
94:     /**
95:      * Returns the position of the section identified by {@code key} in the ordered table
96:      * of contents, matching either its id or its map key.
97:      */
98:     private int indexOf(String key) {
99:         List<CodeSection> sections = new ArrayList<>(codeTarget.getSectionsOrdered());
100:         for (int i = 0; i < sections.size(); i++) {
101:             if (key.equals(sections.get(i).getId())) {
102:                 return i;
103:             }
104:         }
105:         throw new IllegalArgumentException("Cannot add section after " + key + ": not found");
106:     }
107:
108:     /**
109:      * Insert a snippet before the first snippet in the section that matches the given criteria.
110:      * @param sectionKey the section identifier
111:      * @param criteria map with optional 'aspect' and/or 'element' keys
112:      * @param content the snippet content to insert
113:      */
114:     public void beforeSnippet(Object sectionKey, Map<String, Object> criteria, String content) {
115:         CodeSection section = codeTarget.getSection(sectionKey);
116:         CodeSnippet target = findMatchingSnippet(section, criteria);
117:         if (target != null) {
118:             String aspect = CodeTargetContext.getActiveContext() != null ?
119:                     String.valueOf(CodeTargetContext.getActiveContext().getAspect()) : "default";
120:             section.insertBefore(target, new SingleLineSnippet(aspect, content));
121:         }
122:     }
123:
124:     /**
125:      * Insert a snippet after the first snippet in the section that matches the given criteria.
126:      * @param sectionKey the section identifier
127:      * @param criteria map with optional 'aspect' and/or 'element' keys
128:      * @param content the snippet content to insert
129:      */
130:     public void afterSnippet(Object sectionKey, Map<String, Object> criteria, String content) {
131:         CodeSection section = codeTarget.getSection(sectionKey);
132:         CodeSnippet target = findMatchingSnippet(section, criteria);
133:         if (target != null) {
134:             String aspect = CodeTargetContext.getActiveContext() != null ?
135:                     String.valueOf(CodeTargetContext.getActiveContext().getAspect()) : "default";
136:             section.insertAfter(target, new SingleLineSnippet(aspect, content));
137:         }
138:     }
139:
140:     private CodeSnippet findMatchingSnippet(CodeSection section, Map<String, Object> criteria) {
141:         String aspect = (String) criteria.get("aspect");
142:         ModelElement element = (ModelElement) criteria.get("element");
143:
144:         for (CodeSnippet snippet : section.getSnippetsOrdered()) {
145:             // A null criterion matches any value, so callers can filter by aspect alone,
146:             // element alone, or both.
147:             boolean aspectMatches = aspect == null || (snippet.getAspect() != null && snippet.getAspect().equals(aspect));
148:             boolean elementMatches = element == null || (snippet.getModelElement() != null && snippet.getModelElement().equals(element));
149:             if (aspectMatches && elementMatches) {
150:                 return snippet;
151:             }
152:         }
153:         return null;
154:     }
155:
156:     /**
157:      * Returns the current ModelElement (mClass) from the active CodeTargetContext.
158:      */
159:     public ModelElement getMClass() {
160:         if (CodeTargetContext.getActiveContext() != null) {
161:             return CodeTargetContext.getActiveContext().getModelElement();
162:         }
163:         return null;
164:     }
165: }

---

## Assistant

_Thinking:_

Next, let's look at how CodeTarget is used in the actual generator — PoJoCodeTargetCreator and JavaSections, plus CodeTargetToCodeConverter. Also the documentation: doc/CodeTargetTemplates (empty?) and todos/CodeTargetImprovements.md. Let's check the documentation directory, and also take a look at how CodeTarget is used in the generator pipeline (e.g., inside cgv19-core's generators).







**Tool: read**

**Input:**
```json
{
  "path": "/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/main/java/de/spraener/nxtgen/target/CodeTargetToCodeConverter.java"
}
```

Read file /Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/main/java/de/spraener/nxtgen/target/CodeTargetToCodeConverter.java, lines 1-114
1: package de.spraener.nxtgen.target;
2:
3: import de.spraener.nxtgen.model.ModelHelper;
4:
5: /**
6:  * <strong>Responsibility</strong>
7:  * A CodeTargetToCodeConverter converts a CodeTarget holding
8:  * CodeSections which holds CodeSnippets to a single String
9:  * of text. It reads all CodeSections from the target and from
10:  * each such CodeSection the CodeSnippets. Each CodeSnippets
11:  * content is than appended to a StringBuilder.
12:  *
13:  * The converting is implemented in the "toString()" method.
14:  */
15: public class CodeTargetToCodeConverter {
16:     private static final String INDENT_UNIT = "    ";
17:
18:     private CodeTarget codeTarget;
19:     private boolean withMarkers = false;
20:     private String singleLineCommentPrefix = "//";
21:
22:     public CodeTargetToCodeConverter(CodeTarget codeTarget) {
23:         this.codeTarget = codeTarget;
24:     }
25:
26:     public String toString() {
27:         StringBuilder sb = new StringBuilder();
28:         for( CodeSection section : this.codeTarget.getSectionsOrdered() ) {
29:             renderSection(sb, section, 0);
30:         }
31:         return sb.toString();
32:     }
33:
34:     private void renderSection(StringBuilder sb, CodeSection section, int indentLevel) {
35:         String pad = INDENT_UNIT.repeat(indentLevel);
36:         if( withMarkers ) {
37:             sb.append(String.format("%n%s<<section id=%s>>%n", this.singleLineCommentPrefix, section.getId()));
38:         }
39:         if( section.rendersChildrenInline() ) {
40:             for( CodeSnippet snippet : section.getSnippetsOrdered() ) {
41:                 renderSnippet(sb, snippet, pad);
42:             }
43:         } else {
44:             for( CodeSnippet snippet : section.getOwnSnippets() ) {
45:                 renderSnippet(sb, snippet, pad);
46:             }
47:             for( CodeSection child : section.getChildren() ) {
48:                 renderSection(sb, child, indentLevel + 1);
49:             }
50:         }
51:         if( withMarkers ) {
52:             sb.append(String.format("%n%s<</section:%s>>%n", this.singleLineCommentPrefix, section.getId()));
53:         }
54:     }
55:
56:     private void renderSnippet(StringBuilder sb, CodeSnippet snippet, String pad) {
57:         if( withMarkers ) {
58:             String fqName = "";
59:             if( snippet.getModelElement()!=null ) {
60:                 fqName = " modelELement="+ModelHelper.getFQName(snippet.getModelElement(), ".");
61:             }
62:
63:             sb.append(String.format("%n%s<<snippet aspect=%s%s>>%n", this.singleLineCommentPrefix, snippet.getAspect(), fqName));
64:         }
65:         StringBuilder snippetSb = new StringBuilder();
66:         snippet.evaluate(snippetSb);
67:         appendIndented(sb, snippetSb.toString(), pad);
68:         if( withMarkers ) {
69:             sb.append(String.format("%n%s<</snippet:%s>>%n", this.singleLineCommentPrefix, snippet.getAspect()));
70:         }
71:     }
72:
73:     /**
74:      * Appends evaluated snippet code. With a non-empty pad, every non-empty line is prefixed
75:      * with the pad; the original newline structure is preserved. With an empty pad (depth 0)
76:      * the code is appended as-is, byte-identical to the former flat rendering.
77:      */
78:     private void appendIndented(StringBuilder sb, String code, String pad) {
79:         if( pad.isEmpty() ) {
80:             sb.append(code);
81:             return;
82:         }
83:         int start = 0;
84:         for( int i = 0; i <= code.length(); i++ ) {
85:             boolean atLineEnd = (i == code.length()) || code.charAt(i) == '\n';
86:             if( !atLineEnd ) {
87:                 continue;
88:             }
89:             String line = code.substring(start, i);
90:             if( !line.isEmpty() && atLineStart(sb) ) {
91:                 sb.append(pad);
92:             }
93:             sb.append(line);
94:             if( i < code.length() ) {
95:                 sb.append('\n');
96:             }
97:             start = i + 1;
98:         }
99:     }
100:
101:     private boolean atLineStart(StringBuilder sb) {
102:         return sb.length() == 0 || sb.charAt(sb.length() - 1) == '\n';
103:     }
104:
105:     public CodeTargetToCodeConverter withSingleLineCommentPrefix( String singleLineCommentPrefix ) {
106:         this.singleLineCommentPrefix = singleLineCommentPrefix;
107:         return this;
108:     }
109:
110:     public CodeTargetToCodeConverter withMarkers(boolean withMarkers) {
111:         this.withMarkers = withMarkers;
112:         return this;
113:     }
114: }

**Tool: read**

**Input:**
```json
{
  "path": "/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/main/java/de/spraener/nxtgen/target/java/JavaSections.java"
}
```

Read file /Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/main/java/de/spraener/nxtgen/target/java/JavaSections.java, lines 1-37
1: package de.spraener.nxtgen.target.java;
2:
3: import de.spraener.nxtgen.target.*;
4:
5: public enum JavaSections {
6:     HEADER,
7:     IMPORTS,
8:     CLASS_DECLARATION,
9:     EXTENDS,
10:     IMPLEMENTS,
11:     CLASS_BLOCK_BEGIN,
12:     CONSTRUCTORS,
13:     ATTRIBUTE_DECLARATIONS,
14:     METHODS,
15:     CLASS_BLOCK_ENDS;
16:
17:     public static CodeTarget createJavaCodeTarget(String... preambleLines) {
18:         CodeTarget codeTarget = new CodeTarget();
19:         codeTarget.addCodeSection(HEADER, new SimpleCodeSection());
20:         codeTarget.addCodeSection(IMPORTS, new UniqueLineSection().add(new SingleLineSnippet("java", null, "")));
21:         codeTarget.addCodeSection(CLASS_DECLARATION, new SimpleCodeSection().add(new SingleLineSnippet("java", null, "")));
22:         codeTarget.addCodeSection(EXTENDS, new SimpleCodeSection());
23:         codeTarget.addCodeSection(IMPLEMENTS, new JavaImplementsCodeSection());
24:         codeTarget.addCodeSection(CLASS_BLOCK_BEGIN, new SimpleCodeSection().add(new SingleLineSnippet("java", null, "{")));
25:         codeTarget.addCodeSection(ATTRIBUTE_DECLARATIONS, new SimpleCodeSection().add(new SingleLineSnippet("java", null, "")));
26:         codeTarget.addCodeSection(CONSTRUCTORS, new SimpleCodeSection().add(new SingleLineSnippet("java", null, "")));
27:         codeTarget.addCodeSection(METHODS, new SimpleCodeSection().add(new SingleLineSnippet("java", null, "")));
28:         codeTarget.addCodeSection(CLASS_BLOCK_ENDS, new SimpleCodeSection().add(new SingleLineSnippet("java", null, "}")));
29:         if( preambleLines!=null ) {
30:             for( String preamble : preambleLines ) {
31:                 codeTarget.getSection(HEADER)
32:                         .add(new SingleLineSnippet("preamble", preamble));
33:             }
34:         }
35:         return codeTarget;
36:     }
37: }

---

## Assistant

_Thinking:_

Next, let's take a look at how CodeTarget is used in the actual generator. I'll check PoJoCodeTargetCreator, and also look at how CodeTarget is used in the generator pipeline (e.g., inside cgv19-core's generators). Also, I'll check the documentation: doc/CodeTargetTemplates (empty?) and todos/CodeTargetImprovements.md. Let's check the documentation directory, and also take a look at how CodeTarget is used in the generator pipeline (e.g., inside cgv19-core's generators).







**Tool: read**

**Input:**
```json
{
  "path": "/Users/casi/Projekte/Java/cgV19/core/cgv19-pojo/src/main/java/de/spraener/nxtgen/pojo/PoJoCodeTargetCreator.java"
}
```

Read file /Users/casi/Projekte/Java/cgV19/core/cgv19-pojo/src/main/java/de/spraener/nxtgen/pojo/PoJoCodeTargetCreator.java, lines 1-47
1: package de.spraener.nxtgen.pojo;
2:
3: import de.spraener.nxtgen.NextGen;
4: import de.spraener.nxtgen.cartridges.EvaluationRequest;
5: import de.spraener.nxtgen.oom.model.MActivity;
6: import de.spraener.nxtgen.oom.model.MClass;
7: import de.spraener.nxtgen.target.CodeTarget;
8:
9: public class PoJoCodeTargetCreator {
10:     public static final String POJO_ASPECT = "pojo-frame";
11:     public static final String ATTRIBUTE_ASPECT = "pojo-attribute";
12:     public static final String ASSOCIATION = "pojo-association";
13:     private MClass mClass;
14:
15:     public PoJoCodeTargetCreator(MClass mClass) {
16:         this.mClass = mClass;
17:     }
18:
19:     public CodeTarget createPoJoTarget() {
20:         CodeTarget target = new ClassFrameTargetCreator(this.mClass).createPoJoTarget();
21:         MClass orgClass = PoJoGenerator.getOriginalClass(this.mClass);
22:         if( orgClass == null ) {
23:             orgClass = mClass;
24:         }
25:         if( orgClass!=null ) {
26:             final MClass pojo = orgClass;
27:             target.forAspect(POJO_ASPECT, pojo,
28:                     ct -> new PoJoAttributesCreator().accept(ct, pojo),
29:                     ct -> new PoJoAssociationCreator().accept(ct, pojo),
30:                     ct -> {
31:                         // if the pojo has any activities try to resolve them with another cartridge
32:                         // that supports activity generation.
33:                         for(MActivity activity : pojo.getActivities() ) {
34:                             EvaluationRequest activityRequest = new EvaluationRequest(
35:                                     activity,
36:                                     PoJoCartridge.POJO_STEREOTYPE,
37:                                     PoJoCartridge.ACTIVITY_ASPECT,
38:                                     ct);
39:                             NextGen.evaluateByAny(activityRequest);
40:                         }
41:                     }
42:             );
43:         }
44:         return target;
45:     }
46:
47: }

**Tool: shell**

**Input:**
```json
{
  "command": "ls -la /Users/casi/Projekte/Java/cgV19/core/cgv19-core/doc/CodeTargetTemplates /Users/casi/Projekte/Java/cgV19/core/cgv19-core/doc/todos 2>/dev/null; grep -rl \"CodeTarget\" /Users/casi/Projekte/Java/cgV19/core --include=\"*.java\" --include=\"*.groovy\" -l | grep -v build/ | grep -v test | head -30"
}
```

/Users/casi/Projekte/Java/cgV19/core/cgv19-core/doc/CodeTargetTemplates:
total 0
drwxr-xr-x@ 2 casi  staff   64 31 Aug. 12:19 .
drwxr-xr-x@ 6 casi  staff  192 11 Sep. 06:40 ..

/Users/casi/Projekte/Java/cgV19/core/cgv19-core/doc/todos:
total 40
drwxr-xr-x@ 5 casi  staff   160 11 Sep. 11:03 .
drwxr-xr-x@ 6 casi  staff   192 11 Sep. 06:40 ..
-rw-r--r--@ 1 casi  staff  8008 11 Sep. 09:31 CodeSectionImprovments.md
-rw-r--r--@ 1 casi  staff  2057 11 Sep. 06:48 CodeTargetImprovements.md
-rw-r--r--@ 1 casi  staff  7651 11 Sep. 11:34 OperationSectionSpec.md
/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/main/java/de/spraener/nxtgen/cartridges/EvaluationRequest.java
/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/main/java/de/spraener/nxtgen/target/CodeTargetToCodeConverter.java
/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/main/java/de/spraener/nxtgen/target/CodeTargetCodeBlockAdapter.java
/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/main/java/de/spraener/nxtgen/target/JavaRenderer.java
/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/main/java/de/spraener/nxtgen/target/CodeSnippet.java
/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/main/java/de/spraener/nxtgen/target/CodeTargetRenderer.java
/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/main/java/de/spraener/nxtgen/target/typescript/TypeScriptSections.java
/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/main/java/de/spraener/nxtgen/target/CodeTargetContext.java
/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/main/java/de/spraener/nxtgen/target/java/JavaSections.java
/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/main/java/de/spraener/nxtgen/target/SingleLineSnippet.java
/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/main/java/de/spraener/nxtgen/target/CodeTarget.java
/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/main/java/de/spraener/nxtgen/target/CodeSnippetRef.java
/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/main/java/de/spraener/nxtgen/target/CodeBlockSnippet.java
/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/main/java/de/spraener/nxtgen/target/dsl/ForAspectDSL.java
/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/main/java/de/spraener/nxtgen/target/package-info.java
/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/main/java/de/spraener/nxtgen/GroovyCodeBlockImpl.java
/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/main/java/de/spraener/nxtgen/visitor/ElementContext.java
/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/main/java/de/spraener/nxtgen/visitor/TreeWalker.java
/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/main/groovy/de/spraener/nxtgen/target/dsl/CodeTargetDSL.groovy
/Users/casi/Projekte/Java/cgV19/core/cgv19-pojo/src/main/java/de/spraener/nxtgen/pojo/SerializableEnhancer.java
/Users/casi/Projekte/Java/cgV19/core/cgv19-pojo/src/main/java/de/spraener/nxtgen/pojo/PoJoAssociationCreator.java
/Users/casi/Projekte/Java/cgV19/core/cgv19-pojo/src/main/java/de/spraener/nxtgen/pojo/TypeScriptCreator.java
/Users/casi/Projekte/Java/cgV19/core/cgv19-pojo/src/main/java/de/spraener/nxtgen/pojo/PoJoGenerator.java
/Users/casi/Projekte/Java/cgV19/core/cgv19-pojo/src/main/java/de/spraener/nxtgen/pojo/PoJoAttributesCreator.java
/Users/casi/Projekte/Java/cgV19/core/cgv19-pojo/src/main/java/de/spraener/nxtgen/pojo/PoJoCartridge.java
/Users/casi/Projekte/Java/cgV19/core/cgv19-pojo/src/main/java/de/spraener/nxtgen/pojo/ClassFrameTargetCreator.java
/Users/casi/Projekte/Java/cgV19/core/cgv19-pojo/src/main/java/de/spraener/nxtgen/pojo/PoJoFSMMethodCreator.java
/Users/casi/Projekte/Java/cgV19/core/cgv19-pojo/src/main/java/de/spraener/nxtgen/pojo/PoJoCodeTargetCreator.java
/Users/casi/Projekte/Java/cgV19/core/cgv19-pojo/src/main/java/de/spraener/nxtgen/pojo/TypeScriptAttributes.java
/Users/casi/Projekte/Java/cgV19/core/cgv19-metacartridge/src/main/resources/meta/CartridgeImplTemplate.groovy


---

## Assistant

_Thinking:_

Let's check a few more important parts: package-info.java (which probably has an overview), CodeTargetCodeBlockAdapter, EvaluationRequest, and how the generator uses CodeTarget (PoJoGenerator). Also, I'll check the documentation todos for context. Let me read package-info.java and CodeTargetCodeBlockAdapter, plus EvaluationRequest.







**Tool: read**

**Input:**
```json
{
  "path": "/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/main/java/de/spraener/nxtgen/target/package-info.java"
}
```

Read file /Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/main/java/de/spraener/nxtgen/target/package-info.java, lines 1-23
1: /**
2:  * @startuml class CodeTarget
3:  * class CodeSection
4:  * class CodeSnippet {
5:  * Object aspect
6:  * ModelElement me
7:  * }
8:  * CodeTarget *--> "0..n" CodeSection
9:  * CodeSection *--> "0..n" CodeSnippet
10:  * CodeSnippet *---> "beforeMe (0..n)" CodeSnippet
11:  * CodeSnippet *---> "afterMe (0..n)" CodeSnippet
12:  * @enduml
13:  */
14: /**
15:  * <h2>CodeTarget base code generators</h2>
16:  * <p>
17:  * A generator on base of a CodeTarget does not generate a String with the
18:  * code but a CodeTarget that can be modified by other CodeTarget base
19:  * generators. The goal is to make the result of one generator usable to
20:  * other generators. This can be the base of reusable generators in a
21:  * generator library.
22:  */
23: package de.spraener.nxtgen.target;

**Tool: read**

**Input:**
```json
{
  "path": "/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/main/java/de/spraener/nxtgen/target/CodeTargetCodeBlockAdapter.java"
}
```

Read file /Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/main/java/de/spraener/nxtgen/target/CodeTargetCodeBlockAdapter.java, lines 1-39
1: package de.spraener.nxtgen.target;
2:
3: import de.spraener.nxtgen.CodeBlockImpl;
4:
5: public class CodeTargetCodeBlockAdapter extends CodeBlockImpl {
6:     private CodeTarget codeTarget;
7:     private boolean withMarkers = false;
8:     private String commentPrefix = "//";
9:     /**
10:      * Create a new CodeBlock for the given codeTarget.
11:      *
12:      * @param codeTarget a CodeTarget this adapter operates on.
13:      */
14:     public CodeTargetCodeBlockAdapter(CodeTarget codeTarget) {
15:         super("CodeTargetAdapter");
16:         this.codeTarget = codeTarget;
17:     }
18:
19:     public CodeTargetCodeBlockAdapter withCommentPrefix(String singleLineCommentPrefix) {
20:         this.commentPrefix = singleLineCommentPrefix;
21:         return this;
22:     }
23:
24:     public CodeTargetCodeBlockAdapter withMarkers() {
25:         this.withMarkers = true;
26:         return this;
27:     }
28:     @Override
29:     public String toCode() {
30:         CodeTargetToCodeConverter codeConverter = new CodeTargetToCodeConverter(this.codeTarget)
31:                 .withMarkers(this.withMarkers)
32:                 .withSingleLineCommentPrefix(this.commentPrefix)
33:                 ;
34:         StringBuilder sb = new StringBuilder();
35:         sb.append(codeConverter.toString());
36:         sb.append(super.toCode());
37:         return sb.toString();
38:     }
39: }

---

## Assistant

_Thinking:_

Next, let's check how the generator pipeline uses CodeTarget — I'll look at PoJoGenerator and EvaluationRequest, plus ClassFrameTargetCreator to see how the target is created. Also, I'll check where CodeTargetCodeBlockAdapter / CodeTargetToCodeConverter are used in the generator flow (e.g., inside NextGen or the cartridge evaluation). Let me grep for usages of CodeTargetToCodeConverter and CodeTargetCodeBlockAdapter.






**Tool: shell**

**Input:**
```json
{
  "command": "grep -rn \"CodeTargetToCodeConverter\\|CodeTargetCodeBlockAdapter\" /Users/casi/Projekte/Java/cgV19/core --include=\"*.java\" --include=\"*.groovy\" | grep -v \"/build/\" | grep -v \"target/CodeTargetToCodeConverter.java\\|target/CodeTargetCodeBlockAdapter.java\" | head -20"
}
```

/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/test/java/de/spraener/nxtgen/target/CodeTargetGetOrCreateTest.java:189:        String out = new CodeTargetToCodeConverter(target).toString();
/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/test/java/de/spraener/nxtgen/target/CodeTargetModificationTest.java:51:        assertThat(new CodeTargetToCodeConverter(target).toString())
/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/test/java/de/spraener/nxtgen/target/CodeTargetModificationTest.java:74:        assertThat(new CodeTargetToCodeConverter(target).toString())
/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/test/java/de/spraener/nxtgen/target/CodeTargetModificationTest.java:89:        String code = new CodeTargetToCodeConverter(target).toString();
/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/test/java/de/spraener/nxtgen/target/CodeTargetToCodeConverterTest.java:8:public class CodeTargetToCodeConverterTest {
/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/test/java/de/spraener/nxtgen/target/CodeTargetToCodeConverterTest.java:18:        String code = new CodeTargetToCodeConverter(target).toString();
/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/test/java/de/spraener/nxtgen/target/CodeTargetToCodeConverterTest.java:32:        String code = new CodeTargetToCodeConverter(target).toString();
/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/test/java/de/spraener/nxtgen/target/CodeTargetToCodeConverterTest.java:45:        String code = new CodeTargetToCodeConverter(target).toString();
/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/test/java/de/spraener/nxtgen/target/CodeTargetToCodeConverterTest.java:59:        String code = new CodeTargetToCodeConverter(target).toString();
/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/test/java/de/spraener/nxtgen/target/CodeTargetToCodeConverterTest.java:73:        String code = new CodeTargetToCodeConverter(target).toString();
/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/test/java/de/spraener/nxtgen/target/CodeTargetToCodeConverterTest.java:89:        String code = new CodeTargetToCodeConverter(target).toString();
/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/test/java/de/spraener/nxtgen/target/CodeTargetToCodeConverterTest.java:103:        String code = new CodeTargetToCodeConverter(target).withMarkers(true).toString();
/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/test/java/de/spraener/nxtgen/target/CodeTargetToCodeConverterTest.java:118:        String code = new CodeTargetToCodeConverter(target).toString();
/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/test/java/de/spraener/nxtgen/target/ObjectMother.java:57:        String code = new CodeTargetToCodeConverter(target).toString();
/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/test/java/de/spraener/nxtgen/target/CodeTargetCodeBlockAdapterTest.java:10:class CodeTargetCodeBlockAdapterTest {
/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/test/java/de/spraener/nxtgen/target/CodeTargetCodeBlockAdapterTest.java:15:        assertThat(new CodeTargetCodeBlockAdapter(pojoTarget).toCode())
/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/test/java/de/spraener/nxtgen/target/UniqueLineSectionTest.java:33:                new CodeTargetToCodeConverter(target).toString());
/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/main/java/de/spraener/nxtgen/GroovyCodeBlockImpl.java:6:import de.spraener.nxtgen.target.CodeTargetToCodeConverter;
/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/main/java/de/spraener/nxtgen/GroovyCodeBlockImpl.java:74:                return new CodeTargetToCodeConverter(ct).toString();
/Users/casi/Projekte/Java/cgV19/core/cgv19-pojo/src/test/java/de/spraener/nxtgen/pojo/PoJoCodeTargetCreatorTest.java:6:import de.spraener.nxtgen.target.CodeTargetToCodeConverter;


---

## Assistant

_Thinking:_

Let's look at GroovyCodeBlockImpl to understand how CodeTarget is integrated into the generator pipeline, and also check EvaluationRequest. Also, let's take a look at ClassFrameTargetCreator to see how the target is constructed and used.







**Tool: read**

**Input:**
```json
{
  "path": "/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/main/java/de/spraener/nxtgen/GroovyCodeBlockImpl.java"
}
```

Read file /Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/main/java/de/spraener/nxtgen/GroovyCodeBlockImpl.java, lines 1-83
1: package de.spraener.nxtgen;
2:
3: import de.spraener.nxtgen.model.ModelElement;
4: import de.spraener.nxtgen.target.CodeTarget;
5: import de.spraener.nxtgen.target.CodeTargetRenderer;
6: import de.spraener.nxtgen.target.CodeTargetToCodeConverter;
7: import groovy.lang.Binding;
8: import groovy.lang.GroovyShell;
9: import groovy.lang.Script;
10:
11: import java.io.BufferedReader;
12: import java.io.IOException;
13: import java.io.InputStreamReader;
14: import java.net.MalformedURLException;
15: import java.net.URL;
16:
17:
18: /**
19:  * This CodeBlock implements a bridge to groovy generator templates. It takes a name,
20:  * a ModelElement and a URL to the graoovy script that generates the output.
21:  *
22:  */
23: public class GroovyCodeBlockImpl extends CodeBlockImpl {
24:     private ModelElement me = null;
25:     private String templateScript;
26:     private String templateScriptURL;
27:
28:     public GroovyCodeBlockImpl(String name, ModelElement me, String templateScriptURL) {
29:         super(name);
30:         this.me = me;
31:         this.templateScriptURL = templateScriptURL;
32:         try {
33:             InputStreamReader reader = toInputStreamReader(this.templateScriptURL);
34:             BufferedReader templateReader = new BufferedReader(reader);
35:             StringBuilder sb = new StringBuilder();
36:             String line;
37:             while ((line = templateReader.readLine()) != null) {
38:                 sb.append(line).append('\n');
39:             }
40:             this.templateScript = sb.toString();
41:         } catch (IOException ioXc) {
42:             throw new RuntimeException("Could not read from Scripts URL '" + templateScriptURL + "'. Check Claspath and URL form.", ioXc);
43:         }
44:     }
45:
46:     private InputStreamReader toInputStreamReader(String templateScriptURL) throws IOException {
47:         try {
48:             return new InputStreamReader(new URL(templateScriptURL).openStream());
49:         } catch (MalformedURLException mfuExc) {
50:             return new InputStreamReader(GroovyCodeBlockImpl.class.getResourceAsStream(templateScriptURL));
51:         }
52:     }
53:
54:
55:     @Override
56:     public String toCode() {
57:         Binding b = new Binding();
58:         b.setVariable("modelElement", this.me);
59:         b.setVariable("codeBlock", this);
60:         b.setVariable("mClass", this.me);  // Alias for modelElement
61:         b.setProperty("modelElement", this.me);
62:         b.setProperty("codeBlock", this);
63:         b.setProperty("mClass", this.me);   // Alias for modelElement
64:         GroovyShell shell = new GroovyShell(b);
65:         Script scr = shell.parse(templateScript,templateScriptURL);
66:         try {
67:             Object value = scr.run();
68:
69:             if (value instanceof CodeTarget ct) {
70:                 CodeTargetRenderer renderer = ct.getRenderer();
71:                 if (renderer != null) {
72:                     return renderer.render(ct);
73:                 }
74:                 return new CodeTargetToCodeConverter(ct).toString();
75:             }
76:
77:             return value.toString();
78:         } catch (Exception e) {
79:             NextGen.LOGGER.severe(() -> "Error while executing script " + this.templateScriptURL + ": "+e.getMessage());
80:             throw new NxtGenRuntimeException(e);
81:         }
82:     }
83: }

**Tool: read**

**Input:**
```json
{
  "path": "/Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/main/java/de/spraener/nxtgen/cartridges/EvaluationRequest.java"
}
```

Read file /Users/casi/Projekte/Java/cgV19/core/cgv19-core/src/main/java/de/spraener/nxtgen/cartridges/EvaluationRequest.java, lines 1-104
1: package de.spraener.nxtgen.cartridges;
2:
3: import de.spraener.nxtgen.model.ModelElement;
4: import de.spraener.nxtgen.model.Stereotype;
5: import de.spraener.nxtgen.target.CodeTarget;
6:
7: /**
8:  * An EvaluationRequest is given from a generating cartridge to
9:  * cgV19. cgV19 will ask all known cartridges if they are able
10:  * to fulfill the request. If such a cartridge is found, the request
11:  * is given to that cartridge to generate a CodeBlock for the
12:  * calling cartridge. This way a cartridge can make use of generator logic
13:  * from another cartridge.
14:  */
15: public class EvaluationRequest {
16:     /**
17:      * The ModelElement that needs a sub evaluation
18:      */
19:     private ModelElement me;
20:
21:     /**
22:      * A Stereotype that triggered the request
23:      */
24:     private Stereotype sType;
25:
26:     /**
27:      * An aspect that needs to be evaluated. For example, a method
28:      * implementation for an FSM
29:      */
30:     private String aspect;
31:
32:     /**
33:      * A sub aspect of the aspect. For example, required imports, fields
34:      * or concrete implementation.
35:      */
36:     private String subAspect;
37:
38:     /**
39:      * A CodeTarget to take the implementation. This is optional. It can only
40:      * be taken from cartridges that support code target-based generation.
41:      * <p>
42:      * Note: If you set a CodeTarget on the request and the receiving cartridge can
43:      * handle it, the receiving cartridge is able to fulfill all necessary sections in
44:      * one request. It can add imports, attribute declarations, and method implementation
45:      * into the given CodeTarget.
46:      */
47:     private CodeTarget codeTarget;
48:     private String srcLanguage;
49:
50:     public EvaluationRequest(ModelElement me, Stereotype sType, String aspect, String subAspect) {
51:         this("java", me, sType, aspect, subAspect);
52:     }
53:
54:     public EvaluationRequest(ModelElement me, Stereotype sType, String aspect, CodeTarget codeTarget) {
55:         this("java", me, sType, aspect, null);
56:         this.codeTarget = codeTarget;
57:     }
58:
59:     public EvaluationRequest(String srcLanguage, ModelElement me, Stereotype sType, String aspect, String subAspect) {
60:         this.srcLanguage = srcLanguage;
61:         this.me = me;
62:         this.sType = sType;
63:         this.aspect = aspect;
64:         this.subAspect = subAspect;
65:     }
66:
67:     public EvaluationRequest withCodeTarget(CodeTarget codeTarget) {
68:         this.codeTarget = codeTarget;
69:         return this;
70:     }
71:
72:     public ModelElement getMe() {
73:         return me;
74:     }
75:
76:     public String getAspect() {
77:         return aspect;
78:     }
79:
80:     public String getSubAspect() {
81:         return subAspect;
82:     }
83:
84:     public CodeTarget getCodeTarget() {
85:         return codeTarget;
86:     }
87:
88:     public Stereotype getStereotype() {
89:         return sType;
90:     }
91:
92:     @Override
93:     public String toString() {
94:         return "EvaluationRequest{" +
95:                 "me= <<"+sType.getName()+">> "+me+
96:                 ", aspect.subAspect='" + aspect + '.' + subAspect + '\'' +
97:                 ", codeTarget=" + codeTarget +
98:                 '}';
99:     }
100:
101:     public String getSourceLanguage() {
102:         return srcLanguage;
103:     }
104: }

---

## Assistant

_Thinking:_

Now I've got a good grasp of the overall picture. Let me also quickly check ClassFrameTargetCreator to see how the target is constructed, and take a look at the documentation todos for context on recent improvements. I'll also check how PoJoGenerator uses CodeTarget (toCode). Let me quickly read through ClassFrameTargetCreator and the documentation todos.







**Tool: read**

**Input:**
```json
{
  "path": "/Users/casi/Projekte/Java/cgV19/core/cgv19-pojo/src/main/java/de/spraener/nxtgen/pojo/ClassFrameTargetCreator.java"
}
```

Read file /Users/casi/Projekte/Java/cgV19/core/cgv19-pojo/src/main/java/de/spraener/nxtgen/pojo/ClassFrameTargetCreator.java, lines 1-90
1: package de.spraener.nxtgen.pojo;
2:
3: import de.spraener.nxtgen.ProtectionStrategieDefaultImpl;
4: import de.spraener.nxtgen.oom.StereotypeHelper;
5: import de.spraener.nxtgen.oom.model.MClass;
6: import de.spraener.nxtgen.oom.model.MDependency;
7: import de.spraener.nxtgen.oom.model.OOModel;
8: import de.spraener.nxtgen.target.CodeBlockSnippet;
9: import de.spraener.nxtgen.target.CodeSection;
10: import de.spraener.nxtgen.target.CodeTarget;
11: import de.spraener.nxtgen.target.SingleLineSnippet;
12: import de.spraener.nxtgen.target.java.JavaSections;
13:
14: public class ClassFrameTargetCreator {
15:     private MClass mClass = null;
16:     public static final String CLAZZ_FRAME = "clazz-frame";
17:     public static final String DEFAULT_CONSTRUCTOR = "clazz-default-constructor";
18:
19:     public ClassFrameTargetCreator(MClass mc) {
20:         this.mClass = mc;
21:     }
22:
23:     public CodeTarget createPoJoTarget() {
24:         CodeTarget target = JavaSections.createJavaCodeTarget("//" + ProtectionStrategieDefaultImpl.GENERATED_LINE);
25:         target.forAspect(CLAZZ_FRAME, mClass,
26:                 this::declarePackage,
27:                 this::declareClazz,
28:                 this::declareExtends,
29:                 this::declareImplements,
30:                 this::createConstructor
31:         );
32:         return target;
33:     }
34:
35:
36:     private void declareImplements(CodeTarget target) {
37:         if (mClass.getDependencies() != null) {
38:             for (MDependency dependency : mClass.getDependencies()) {
39:                 if (StereotypeHelper.hasStereotype(dependency, "Implements")) {
40:                     MClass depTarget = (MClass) ((OOModel) mClass.getModel()).findClassByName(dependency.getTarget());
41:                     addImplements(target, depTarget);
42:                 }
43:             }
44:         }
45:     }
46:
47:     private void declareExtends(CodeTarget t) {
48:         if (mClass.getInheritsFrom() != null) {
49:             MClass superClass = mClass.getInheritsFrom().getMClass(mClass.getModel());
50:             t.getSection(JavaSections.EXTENDS)
51:                     .add(new CodeBlockSnippet("extends " + superClass.getName()));
52:             if( !superClass.getPackage().getName().equals(mClass.getPackage().getName()) ) {
53:                 t.getSection(JavaSections.IMPORTS)
54:                         .add(new SingleLineSnippet("import " + superClass.getFQName() + ";"));
55:             }
56:         }
57:     }
58:
59:     private CodeSection declarePackage(CodeTarget t) {
60:         return t.getSection(JavaSections.HEADER).add(new SingleLineSnippet("package " + mClass.getPackage().getFQName() + ";"));
61:     }
62:
63:     private void declareClazz(CodeTarget t) {
64:         t.getSection(JavaSections.CLASS_DECLARATION)
65:                 .add(new CodeBlockSnippet("public class " + mClass.getName() + " "));
66:     }
67:
68:     protected void createConstructor(CodeTarget target) {
69:         target.forAspect(DEFAULT_CONSTRUCTOR, mClass, t -> {
70:             StringBuilder sb = new StringBuilder();
71:             sb.append("    public " + mClass.getName() + "() {\n");
72:             sb.append("        super();\n");
73:             t.getSection(JavaSections.CONSTRUCTORS)
74:                     .add(new CodeBlockSnippet(sb.toString()));
75:         });
76:         target.forAspect(DEFAULT_CONSTRUCTOR+".close", mClass, t->{
77:             t.getSection(JavaSections.CONSTRUCTORS)
78:                     .add(new SingleLineSnippet("    }"));
79:         });
80:     }
81:
82:     protected void addImplements(CodeTarget target, MClass depTarget) {
83:         target.getSection(JavaSections.IMPLEMENTS)
84:                 .add(new SingleLineSnippet(depTarget.getName()));
85:         if (!depTarget.getPackage().getFQName().equals(mClass.getPackage().getFQName())) {
86:             target.getSection(JavaSections.IMPLEMENTS)
87:                     .add(new SingleLineSnippet("import " + depTarget.getFQName() + ";"));
88:         }
89:     }
90: }

**Tool: read**

**Input:**
```json
{
  "path": "/Users/casi/Projekte/Java/cgV19/core/cgv19-core/doc/todos/CodeTargetImprovements.md"
}
```

Read file /Users/casi/Projekte/Java/cgV19/core/cgv19-core/doc/todos/CodeTargetImprovements.md, lines 1-55
1: **1. Indentation & Zeilen-Management**
2:
3: * Relative Einrückungs-Modi (`indent()` / `dedent()`) im `CodeTarget`-Model verankern.
4: * Zeilenbasierte Normalisierung (Trimming von Whitespaces) für die Duplikat-Erkennung in `UNIQUE_LINES`-Sektionen einführen.
5:
6: **2. Two-Phase-Assembly & Tagging**
7:
8: * Positionierungs-Logik von direkter In-Place-Musterung auf Anchor-IDs umstellen (z. B. `anchor("before-close")`).
9: * Sammelphase (Collect) und Zusammenbau-Phase (Assemble) trennen, um Ausführungsreihenfolgen der Generator-Aspekte zu entkoppeln.
10: * Interne Indexierung/Maps für Snippet-Zugriffe aufbauen, um $O(n^2)$-Suchlaufzeiten beim Rendern zu vermeiden.
11:
12: **3. Validerung & Sektions-Sicherheit**
13:
14: * Explizite Fehlerbehandlung (`UnknownSectionException`) beim Einfügen in nicht deklarierte Sektionen ergänzen.
15: * Dynamische Sektions-Erweiterung (`addSection`) strikt an Ankerpunkte im `tableOfContents` binden.
16:
17: **4. Context-Safety & Debugging**
18:
19: * `forAspect`-Kontextverwaltung über ein `AutoCloseable`- / Lambda-Pattern absichern, um Resource-Leaks bei Ausnahmen zu verhindern.
20: * Schaltbaren Debug-Modus für den Renderer einbauen, der Herkunfts-Kommentare (Aspekt & Modell-Element) im Zielcode generiert.
21:
22: **5. Post-Processing & Tooling**
23:
24: * Pluggable Post-Processor-Schnittstelle im Renderer integrieren, um externe Formatter (`prettier`, `google-java-format` etc.) optional als finale Phase nachzulagern.
25:
26: **6. Vordefinierte Secions**
27: ```java
28: public interface SectionName {
29:     String getName();
30: 
31:     // Standard-Sektionen als vorgegebene Instanzen/Enums
32:     enum Standard implements SectionName {
33:         HEAD,
34:         PREAMBLE,
35:         BEFORE_CODE,
36:         CODE_START,
37:         BEFORE_DECLARATIONS,
38:         IN_DECLARATIONS,
39:         AFTER_DECLARATIONS,
40:         BEFORE_INITIALIZATION,
41:         IN_INITIALIZATION,
42:         AFTER_INITIALIZATION,
43:         BEFORE_OPERATION,
44:         IN_OPERATION,
45:         AFTER_OPERATION,
46:         BEFORE_CODE_END,
47:         TAIL;
48: 
49:         @Override
50:         public String getName() {
51:             return name();
52:         }
53:     }
54: }
55: ```

---

## Assistant

_Thinking:_

Now I've got a comprehensive overall picture. Let me also quickly check how PoJoGenerator uses CodeTarget to generate the final code, and take a look at SingleLineSnippet/CodeBlockSnippet for completeness. Actually, I have enough information to write a good explanation. Let me quickly check PoJoGenerator and see how the target gets converted into code within the generator pipeline.






**Tool: shell**

**Input:**
```json
{
  "command": "grep -n \"CodeTarget\\|toCode\\|createPoJoTarget\" /Users/casi/Projekte/Java/cgV19/core/cgv19-pojo/src/main/java/de/spraener/nxtgen/pojo/PoJoGenerator.java | head -20"
}
```

11:import de.spraener.nxtgen.target.CodeTargetCodeBlockAdapter;
43:        jCB.addCodeBlock(new CodeTargetCodeBlockAdapter(new PoJoCodeTargetCreator(mc).createPoJoTarget()));
57:        jCB.addCodeBlock( new CodeTargetCodeBlockAdapter(new ClassFrameTargetCreator(mc).createPoJoTarget()));


---

## Assistant

_Thinking:_

Now I have the full picture. Let me write a clear explanation in German (the user asked in German).

Structure of the explanation:
1. Purpose / big picture: CodeTarget is an intermediate representation (IR) for generated code — instead of a generator producing a string, it produces a CodeTarget that other generators can modify. This enables composable / reusable generation (cartridges).
2. Model hierarchy: CodeTarget → CodeSection → CodeSnippet, plus aspect + modelElement tagging.
3. Key classes:
    - CodeTarget: ordered map of sections (LinkedHashMap), getSection with path syntax, getOrCreate, forAspect, append, evaluate (Groovy script).
    - CodeSection / AbstractCodeSection: snippets + child scopes, insertBefore/After/replace with recursive owner search, getSnippetsOrdered is depth-first.
    - CodeSnippet: aspect + ModelElement, matches(), evaluate(StringBuilder). Subclasses SingleLineSnippet, CodeBlockSnippet.
    - CodeTargetContext: ThreadLocal stack of (aspect, modelElement); AutoCloseable; snippets automatically pick up the active context.
    - Renderers: CodeTargetToCodeConverter (toString), JavaRenderer, markers for debugging.
    - ForAspectDSL: Groovy DSL with to/first/addSection/beforeSnippet/afterSnippet.
    - CodeTargetCodeBlockAdapter: bridges to the classic CodeBlock pipeline; GroovyCodeBlockImpl renders a returned CodeTarget.
4. How it works in the pipeline (PoJo example): ClassFrameTargetCreator builds a frame with JavaSections.createJavaCodeTarget, forAspect(CLAZZ_FRAME...) adds package/class/extends/implements/constructor; PoJoCodeTargetCreator then forAspect(POJO_ASPECT) adds attributes, associations, and delegates activities to other cartridges via EvaluationRequest with codeTarget. Finally PoJoGenerator wraps it in CodeTargetCodeBlockAdapter → toCode() renders the string.
5. Key mechanisms: aspect-based modification (getSnippetsForAspect, insertBefore the last snippet of an aspect), path addressing "METHODS/IN_OPERATION", scopes.
6. Design intent: two-phase assembly, decoupling of generators; the todo doc mentions planned improvements (anchors, collect/assemble phases).

Keep it structured but not too long. Use German. Include file paths.




# CodeTarget in cgV19 – Erklärung

Der `CodeTarget` ist die **Zwischenrepräsentation (IR) für generierten Code** im cgV19-Generator-Framework. Das Kernprinzip (aus `package-info.java`): Ein Generator erzeugt nicht direkt einen Code-String, sondern einen `CodeTarget`, den **andere Generatoren weitermodifizieren** können. So lassen sich Generatoren aus Cartridges komponieren – z. B. baut ein POJO-Generator das Skelett, und andere Cartridges hängen Attribute, Methoden oder Imports an.

## Das Datenmodell (3 Ebenen)

```
CodeTarget ──*── CodeSection ──*── CodeSnippet
                    │  (child scopes, Baum)
                    └──*── CodeSnippet
```

**`CodeTarget`** (`cgv19-core/src/main/java/de/spraener/nxtgen/target/CodeTarget.java`)
- Hält die Sektionen in einer `LinkedHashMap` → **Insertionsreihenfolge = Ausgabe-Reihenfolge** (die „Tabelle der Sektionen" eines Typs: HEADER, IMPORTS, CLASS_DECLARATION, …).
- `addCodeSection(key, section)` / `withCodeSection(...)` – pro Key nur eine Sektion, Duplikat wirft `IllegalArgumentException`.
- **Pfad-Syntax**: `getSection("METHODS/IN_OPERATION")` adressiert verschachtelte Sektionen – das erste Segment wird als Key (oder Section-Id, z. B. Enum `JavaSections.METHODS`) aufgelöst, weitere Segmente laufen über `CodeSection.getScope(...)`.
- `getOrCreate(path, supplier)` legt fehlende Sektionen/Scopes deterministisch an (Supplier wird max. einmal aufgerufen).
- `forAspect(aspect, modelElement, consumers...)` – öffnet einen Kontext und führt die Consumer aus (s. u.).
- `append(sectionKey, code)` – bequemer Single-Line-Zugriff; nutzt den aktiven Kontext für das Aspect-Tagging.
- `evaluate(scriptPath)` – führt externe Groovy-Skripte mit Binding (`ct`, `mClass`) aus, z. B. für Template-basierte Generatoren.

**`CodeSection` / `AbstractCodeSection`** (`.../target/CodeSection.java`, `AbstractCodeSection.java`)
- Eine Sektion hält eine geordnete Liste von Snippets (`allSnippets`) **plus** benannte Kind-Scopes (`children`, `LinkedHashMap`), d. h. Sektionen bilden einen Baum (z. B. METHODS → Scope pro Operation).
- `getSnippetsOrdered()` aggregiert rekursiv (eigene Snippets, dann Kinder in Insertionsreihenfolge) – das ist die Basis für das Rendern.
- `insertBefore/insertAfter/replace` suchen den Snippet-Eigner **rekursiv im Subbaum** (`findOwnerOf`), man kann also aus einer Elternsektion in einen Kind-Scope einfügen.
- `getSnippetsForAspect(aspect)` / `...AndModelElement(...)` liefern `CodeSnippetRef`s – das ist der zentrale **Modifikationshebel**: ein späterer Generator findet „die letzten Snippets des Aspekts X" und hängt z. B. davor/danach eigene Codezeilen an (`beforeLastSnippetOfAspect` in `CodeTarget`).
- Konkrete Implementierungen: `SimpleCodeSection`, `UniqueLineSection` (entdupliziert Zeilen, z. B. für IMPORTS), `NonEmptyPrefixedListSection`, `JavaImplementsCodeSection`.

**`CodeSnippet`** (`.../target/CodeSnippet.java`)
- Atomares Code-Stück mit zwei Metadaten: **`aspect`** (z. B. `"pojo-attribute"`, `"clazz-frame"`) und optional **`ModelElement me`** (z. B. das `MAttribute`, für das die Zeile da ist).
- `matches(aspect, me)` erlaubt gezieltes Auffinden; `evaluate(StringBuilder)` rendert den Inhalt.
- Wichtige Eigenschaft: Der no-arg-Konstruktor **liest Aspect und ModelElement automatisch aus dem aktiven `CodeTargetContext`** – dadurch müssen Creator die Tags nicht explizit mitschleppen.
- Subklassen: `SingleLineSnippet` (eine Zeile), `CodeBlockSnippet` (mehrzeiliger Block).

## Der Kontext: `CodeTargetContext`

(`.../target/CodeTargetContext.java`) Ein **ThreadLocal-Stack** von `(aspect, modelElement)`-Paaren:
- `AutoCloseable` → wird in `try (...)` verwendet; Nesting ist erlaubt (push/pop).
- `CodeTarget.forAspect(...)` öffnet den Kontext, führt die Consumer/Closure aus und schließt ihn wieder.
- `getActiveContext()` liefert den Top des Stacks (oder ein leeres Empty-Objekt).
- Effekt: Alles, was innerhalb eines `forAspect`-Blocks erzeugt wird (Snippets über `append`, DSL-Aufrufe), trägt automatisch das richtige Aspect-/Element-Tag – und spätere Generatoren können genau diese Snippets wiederfinden.

## DSL & Skripte

- **`ForAspectDSL`** (`.../target/dsl/ForAspectDSL.java`) – Groovy-Closure-Delegate für `forAspect(aspect, closure)`:
    - `to 'imports', "import ...;"` (Snippet anhängen), `first(...)`, `addSection(name, type[, config])` (idempotent), `beforeSnippet/afterSnippet(sectionKey, [aspect:..., element:...], code)` (positioniert relativ zu bestehenden Snippets), `mClass` liefert das aktive ModelElement.
- **`CodeTargetDSL.groovy`** (`.../target/dsl/`) – Groovy-Variante davon.
- **`CodeTarget.evaluate(path)`** lädt Skripte von Classpath/URL/Datei und bindet `ct` + `mClass`.

## Rendern: CodeTarget → String

- **`CodeTargetToCodeConverter`** – `toString()` läuft alle Sektionen in Reihenfolge ab, rendert Snippets (`evaluate`), indiziert Kind-Scopes rekursiv (4er-Spaces pro Ebene); `withMarkers(true)` schreibt Debug-Kommentare (`<<section id=...>>`, `<<snippet aspect=... modelElement=...>>`) in den Output.
- **`JavaRenderer`** (`CodeTargetRenderer`-Implementierung) – neuere, schlankere Variante; Sektionen mit `rendersChildrenInline()` werden flach gerendert.
- **`CodeTargetCodeBlockAdapter`** – Bridge in die klassische `CodeBlockImpl`-Pipeline: `toCode()` rendert den CodeTarget und hängt optional eigenen Code an. So können CodeTarget-basierte Generatoren in bestehende String-basierte Pipelines eingebaut werden.
- **`GroovyCodeBlockImpl.toCode()`** – wenn ein Groovy-Templateskript einen `CodeTarget` zurückgibt, wird er automatisch gerendert (Renderer aus dem Target oder Default-Converter).

## Typische Nutzung (POJO-Beispiel)

1. **`JavaSections.createJavaCodeTarget(...)`** legt die Standard-Sektionen an (HEADER, IMPORTS als `UniqueLineSection`, CLASS_DECLARATION, EXTENDS, IMPLEMENTS, CLASS_BLOCK_BEGIN, ATTRIBUTE_DECLARATIONS, CONSTRUCTORS, METHODS, CLASS_BLOCK_ENDS) inkl. Platzhalter-Snippets (`"{"`, `"}"`).
2. **`ClassFrameTargetCreator.createPoJoTarget()`** füllt per `forAspect("clazz-frame", mClass, ...)` Package, Klassendeklaration, extends/implements (inkl. Import-Erzeugung) und Default-Konstruktor – teils mit verschachtelten Aspekten wie `"clazz-default-constructor"`.
3. **`PoJoCodeTargetCreator.createPoJoTarget()`** nimmt dieses Target und hängt per `forAspect("pojo-frame", pojo, ...)` Attribute- und Assoziations-Creator an; für `MActivity`-Elemente delegiert er über **`EvaluationRequest(me, stereotype, aspect, codeTarget)`** an *andere Cartridges* – die empfangende Cartridge darf dann in **alle** Sektionen des übergebenen Targets schreiben (Imports, Deklarationen, Implementierung in einem Request).
4. **`PoJoGenerator`** packt das fertige Target in einen `CodeTargetCodeBlockAdapter`, dessen `toCode()` den finalen Quelltext rendert.

## Design-Intention & Ausbauplanung

Das Ziel ist **Two-Phase-Assembly**: erst alle Aspekte sammeln (Collect), dann zusammenbauen (Assemble) – dadurch sind Ausführungsreihenfolge und Positionierung entkoppelt. In `cgv19-core/doc/todos/CodeTargetImprovements.md` stehen die nächsten Schritte: Anchor-IDs statt In-Place-Musterung, strikte Collect/Assemble-Trennung, `UnknownSectionException` für undeklarierte Sektionen, Debug-Modus im Renderer (teilweise schon da: `withMarkers`), pluggbare Post-Processoren (Formatter) und vordefinierte Standard-Sektionen (`SectionName.Standard`: `BEFORE_OPERATION`, `IN_OPERATION`, …).

**Kurz:** CodeTarget = geordnete, adressierbare Sektionsstruktur mit aspect-/element-getaggten Snippets; `CodeTargetContext` (ThreadLocal) macht das Tagging implizit; Renderer wandeln es in Code um. Damit wird aus „Generator → String" ein „Generator → modifizierbares Modell", auf dem beliebig viele Cartridges kooperativ aufbauen.
