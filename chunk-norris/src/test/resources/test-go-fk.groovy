
def DEBUG( String msg ) {
    //System.out.println(msg);
}

def updatePage( line, ctx ) {
    def pageNrPatter = /^Projekt Sente.*?Seite\s+(\d+)/
    def match = (line =~ pageNrPatter)
    if (match) {
        def pageNum = match[0][1]
        DEBUG("Update Pagenumber to ${pageNum}")
        ctx.pageNumber = pageNum.toInteger()
    }
}

def updateSubSection(line,ctx) {
    def subSectionPattern = /^(\d+\.\d+\.\d+)\s+(.*)$/
    def isMatch = (line ==~ subSectionPattern)
    if (isMatch && !context.chapter.equals("Preamble")) {
        def match = (line =~subSectionPattern);
        ctx.subSection = match[0][2].trim()
        DEBUG("Update subSection to ${ctx.subSection} in line '${line}'")
    }
}

def updateSection(line,ctx) {
    def sectionPattern = /^(\d+\.\d+)\s+(.*)$/
    def isMatch = (line ==~ sectionPattern)

    if (isMatch && !context.chapter.equals("Preamble")) {
        def match = (line =~sectionPattern);
        ctx.section = match[0][2].trim()
        ctx.subSection = ""
        DEBUG("Update section to ${ctx.section} in line '${line}'")
    }
}

def updateChapter(line,ctx) {
    def chapterPattern = /^(\d+)\.\s+(.*)$/
    def isMatch = (line ==~ chapterPattern)

    if (isMatch && !context.chapter.equals("Preamble")) {
        def match = (line =~chapterPattern);
        ctx.chapter = match[0][2].trim()
        ctx.section = ""
        ctx.subSection = ""
        DEBUG("Update chapter to ${ctx.chapter} in line '${line}'")
    }
}

DEBUG("Handling line '${line}'");

if( context.chapter.equals("") ) {
    context.chapter = "Preamble"
    context.section = "";
    context.subSection = "";
}
if( line.equals("1. EINLEITUNG UND ZIELSETZUNG") ) {
    context.chapter="EINLEITUNG UND ZIELSETZUNG";
}
updatePage(line, context)
updateChapter(line, context)
updateSection(line, context)
updateSubSection(line,context)
