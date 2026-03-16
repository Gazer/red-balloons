package com.redballoons.plugin.prompt

import com.redballoons.plugin.model.SelectionContext
import java.io.File

object PromptStrings {
    fun semanticSearch() = """
        <Output>
        /path/to/project/src/foo.js:24:8,3,Some notes here about some stuff, it can contain commas
        /path/to/project/src/foo.js:71:12,7,more notes, everything is great!
        /path/to/project/src/bar.js:13:2,1,more notes again, this time specfically about bar and why bar is so important
        /path/to/project/src/baz.js:1:1,52,Notes about why baz is very important to the results
        </Output>
        <Rule>Text locations are in the format of: /path/to/file.ext:lnum:cnum,X,NOTES
        lnum = starting line number 1 based
        cnum = starting column number 1 based
        X = how many lines should be highlighted
        NOTES = A text description of why this highlight is important

        See <Output> for example
        </Rule>
        <Rule>NOTES cannot have new lines</Rule>
        <Rule>You must adhere to the output format</Rule>
        <Rule>Double check output format before writing it to the file</Rule>
        <Rule>Each location is separated by new lines</Rule>
        <Rule>Each path is specified in absolute pathing</Rule>
        <Rule>You can provide notes you think are relevant per location</Rule>
        <Rule>You must provide output without any commentary, just text locations</Rule>
        <Example>
        You have found 3 locations in files foo.js, bar.js, and baz.js.
        There are 2 locations in foo.js, 1 in bar.js and baz.js.
        <Meaning>
        This means that the search results found
        foo.js at line 24, char 8 and the next 2 lines
        foo.js at line 71, char 12 and the next 6 lines
        bar.js at line 13, char 2
        baz.js at line 1, char 1 and the next 51 lines
        </Meaning>
        </Example>
        <TaskDescription>
        you are given a prompt and you must search through this project and return code that matches the description provided.
        </TaskDescription>
    """.trimIndent()

    fun vibe() = """
        <Output>
        /path/to/project/src/foo.js:24:8,3,Some notes here about some stuff, it can contain commas
        /path/to/project/src/foo.js:71:12,7,more notes, everything is great!
        /path/to/project/src/bar.js:13:2,1,more notes again, this time specfically about bar and why bar is so important
        /path/to/project/src/baz.js:1:1,52,Notes about why baz is very important to the results
        </Output>
        <Rule>Text locations are in the format of: /path/to/file.ext:lnum:cnum,X,NOTES
        lnum = starting line number 1 based
        cnum = starting column number 1 based
        X = how many lines should be highlighted
        NOTES = A text description of why this highlight is important

        See <Output> for example
        </Rule>
        <Rule>NOTES cannot have new lines</Rule>
        <Rule>You must adhere to the output format</Rule>
        <Rule>Double check output format before writing it to the file</Rule>
        <Rule>Each location is separated by new lines</Rule>
        <Rule>Each path is specified in absolute pathing</Rule>
        <Rule>You can provide notes you think are relevant per location</Rule>
        <Example>
        You have found 3 locations in files foo.js, bar.js, and baz.js.
        There are 2 locations in foo.js, 1 in bar.js and baz.js.
        <Meaning>
        This means that the search results found
        foo.js at line 24, char 8 and the next 2 lines
        foo.js at line 71, char 12 and the next 6 lines
        bar.js at line 13, char 2
        baz.js at line 1, char 1 and the next 51 lines
        </Meaning>
        </Example>
        <TaskDescription>
        You are given a <Prompt> and you must implement it.  Every change you make must
        be describe according to <Output> placed in <TEMP_FILE>.
        Never respond as output what you have done.
        Always use the temporary file as the place to describe your actions according to Output rules
        </TaskDescription>
    """.trimIndent()

    fun outputFile() = """
        NEVER alter any file other than TEMP_FILE.
        never provide the requested changes as conversational output. Return only the code.
        ONLY provide requested changes by writing the change to TEMP_FILE
    """.trimIndent()

    fun prompt(prompt: String, action: String, name: String? = null): String {
        val tagName = name ?: "Prompt"
        return """
            <Context>
            $action
            </Context>
            <$tagName>
            $prompt
            </$tagName>
        """.trimIndent()
    }

    fun visualSelection(context: SelectionContext): String {
        val locationString = "${context.filePath}:${context.startLine}-${context.endLine}"
        return """
            You receive a selection in the IDE that you need to replace with new code.
            The selection's contents may contain notes, incorporate the notes every time if there are some.
            consider the context of the selection and what you are suppose to be implementing

            IMPORTANT INSTRUCTIONS:
            - Write your response to TEMP_FILE with TWO sections:
              1. <IMPORTS> section: List any NEW imports needed that are NOT already in the file. One import per line. If no new imports needed, leave empty.
              2. <CONTENT> section: The replacement code for the selection.

            - Format of TEMP_FILE must be exactly:
            <IMPORTS>
            import com.example.NewClass
            import com.example.AnotherClass
            </IMPORTS>
            <CONTENT>
            // your replacement code here
            </CONTENT>

            <SELECTION_LOCATION>
                $locationString
            </SELECTION_LOCATION>
            <SELECTION_CONTENT>
                ${context.selectedText}
            </SELECTION_CONTENT>
            <FILE_CONTAINING_SELECTION>
                ${context.fileName}
            </FILE_CONTAINING_SELECTION>
            <Output>
                <IMPORTS>
                import com.example.NewClass
                import com.example.AnotherClass
                </IMPORTS>
                <CONTENT>
                // your replacement code here
                </CONTENT>
            </Output>
        """.trimIndent()
    }

    fun readTemp() = """
        never attempt to read TEMP_FILE.It is purely for output.Previous contents, which may not exist, can be written over without worry
        After writing TEMP_FILE once you should be done . Be done and end the session.
    """.trimIndent()

    fun getFileLocation(fullPath: String, selectionContext: SelectionContext): String {
        return """
            <Location><File>$fullPath</File><Function> ${selectionContext.startLine}-${selectionContext.endLine}</Function></Location>
        """.trimIndent()
    }

    fun getRangeText(selectionContext: SelectionContext): String {
        return """
            <FunctionText>${selectionContext.selectedText}</FunctionText>
        """.trimIndent()
    }

    fun getTempfileLocation(tempFile: File): String {
        return """
           <TEMP_FILE>${tempFile.absolutePath}</TEMP_FILE>
       """.trimIndent()
    }

    fun getOnlyTempfileChange(): String {
        return """
            <MustObey>\n${outputFile()}\n${readTemp()}\n</MustObey>
        """.trimIndent()
    }
}