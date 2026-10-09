def name = "dirList"
def description = """
    List files and directories in a given path.
    Useful for exploring project structure and finding relevant files to read.
"""
def parameters = [
    path: "Directory path (relative to project root or absolute). Use '.' for the project root."
]

def execute(params) {
    def path = params.path ?: '.'
    // Handle both absolute and relative paths
    def dir = new File(path)
    if (!dir.isAbsolute()) {
        dir = new File(projectRoot.toFile(), path)
    }

    if (!dir.exists()) return "Error: Directory not found: ${path}"
    if (!dir.isDirectory()) return "Error: Not a directory: ${path}"

    def entries = dir.listFiles()
    if (entries == null || entries.length == 0) return "Directory '${path}' is empty."

    entries = entries.sort { it.name }
    StringBuilder sb = new StringBuilder()
    sb.append("Contents of '${path}':\n")
    for (entry in entries) {
        def prefix = entry.isDirectory() ? "[DIR]  " : "       "
        sb.append(prefix).append(entry.name).append("\n")
    }
    return sb.toString()
}

[name: name, description: description, parameters: parameters, execute: this.&execute]
