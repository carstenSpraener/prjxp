package de.spraener.prjxp.docpipe.prompt;

import com.github.jknack.handlebars.Options;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.configuration.IMockitoConfiguration;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class SourceDumpResolverTest {

    @Test
    void testResolve() throws Exception {
        System.out.println("Running in "+(new File(".").getAbsolutePath()));
        SourceDumpResolver resolver = new SourceDumpResolver();
        Options optionsMock = Mockito.mock(Options.class);
        when(optionsMock.param(any(Integer.class))).thenReturn("src/main/java");
        String prompt = resolver.resolve(new File("."), optionsMock);

    }
}