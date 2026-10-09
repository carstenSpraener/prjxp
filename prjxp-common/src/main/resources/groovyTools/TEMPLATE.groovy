// === TOOL NAME & DESCRIPTION ===
def name = "myTool"          // Unique tool name (used by LLM to call this tool)
def description = """
    Describe what this tool does and when the LLM should use it.
    Be specific about the use case and any limitations.
"""

// === PARAMETERS ===
def parameters = [
    param1: "Description of parameter 1",
    param2: "Description of parameter 2 (optional)"
]

// === EXECUTION LOGIC ===
def execute(params) {
    // Access sandboxed context variables:
    // - projectRoot (Path): The project root directory
    // - baseDir (Path): Working directory
    // - configSubset (ConfigSubset): Safe subset of configuration
    // - log (Logger): SLF4J logger for debug output

    def result = "Your implementation here"
    return result   // Must return a String
}

// === RETURN THE TOOL DEFINITION ===
[name: name, description: description, parameters: parameters, execute: this.&execute]
