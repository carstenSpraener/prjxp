def name = "fileRead"
def description = """
    Read the full content of a source file.
    Use this when you need to examine specific files in detail,
    especially after reviewing source code skeletons.
"""
def parameters = [
    path: "Path to the file (relative to project root or absolute)"
]

def execute(params) {
    def path = params.path
    if (!path) return "Error: No path provided"

    // Handle both absolute and relative paths
    def file = new File(path)
    if (!file.isAbsolute()) {
        file = new File(projectRoot.toFile(), path)
    }

    if (!file.exists()) return "Error: File not found: ${path}"
    if (!file.isFile()) return "Error: Not a file: ${path}"

    def content = file.text
    // Detect language from extension for code block formatting
    def ext = path.contains('.') ? path.substring(path.lastIndexOf('.')) : ''
    return "```${ext}\n${content}\n```"
}

[name: name, description: description, parameters: parameters, execute: this.&execute]
