package de.spraener.prjxp.docpipe.prompt;

import com.github.jknack.handlebars.Options;
import org.apache.commons.io.IOUtils;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.configuration.IMockitoConfiguration;

import java.io.File;
import java.io.FileWriter;

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

    @Test
    void testResolveSourceCodeDump() throws Exception {
        System.out.println("Running in "+(new File(".").getAbsolutePath()));
        SourceDumpResolver uut = new SourceDumpResolver();
        Options optionsMock = Mockito.mock(Options.class);
        when(optionsMock.param(any(Integer.class))).thenReturn("../chunk-norris/src/main/java");
        String dump = uut.resolve(new File("."), optionsMock);
        Assertions.assertThat(dump).isNotEmpty();
        new File("./src/test/tmp").mkdirs();
        IOUtils.write(dump, new FileWriter("./src/test/tmp/src-dmp.txt"));
    }
}