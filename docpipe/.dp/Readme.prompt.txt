Create a Readme as MarkDown to explain the usage of Doc|Pipe. Explain the cli application from a users perspective as easy as possible to understand. Enhance if Possible with examples and ASCII-Diagrams.

IMPORTANT: As a title image use the image that will be provided under the path doc/images/docpipe.png.

CRITICAL INSTRUCTION: Return ONLY the raw Markdown content. Do not wrap the response in markdown code blocks (e.g., do not use ```markdown ... ```). Start directly with the first heading. Do not include any introductory text, pleasantries, or concluding remarks.

The functional usage of Doc|Pipe can be retrieved from the following provided java source code:

Top-Features you should point out are:

* hashing of prompts to prevent unnecessary document generation
* multi LLM mapping to minimize token costs
* Handlebars as root structure for templates
* Groovy inside templates with full access to spring context
* easy extensibility

Add a section für further reading that contains a list of links to the documents as listet here:

* HowTo pointing to doc/HowTo.md which contains explanations for developers on how to use and extend Doc|Pipe
* FAQs pointing to doc/FAQ.md which answers the most important questions about Doc|Pipe
* ArchitectureAssessment pointing to doc/ArchitectureAssessment.md which contains a assessment of the architecture of Doc|Pipe based on its source code.

```java
{{#java-src-dump this ../src/main/java}}{{/java-src-dump}}
```

As a fun fact add a footer that mentions that DocPipe generates its own architecture assessment under doc/ArchitectureAssessment.md and add a link to that file.