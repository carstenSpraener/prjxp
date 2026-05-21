package de.spraener.prjxp.chuno.code.ts;

import de.spraener.prjxp.chuno.PxChunkTestUtil;
import de.spraener.prjxp.chuno.code.typescript.TypeScriptCodeChunker;
import de.spraener.prjxp.common.model.PxChunk;
import org.assertj.core.api.Assertions;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.File;
import java.util.List;
import java.util.stream.Stream;

@SpringBootTest
@ActiveProfiles("test")
public class TypeScriptChunkerTests {

    @Autowired
    TypeScriptCodeChunker uut;

    @Test
    public void testSimpleClass() throws Exception {
        List<PxChunk> chunkList = chunkTypeScriptCode("/ts/simple-test.ts");
        Assertions.assertThat(chunkList)
                .isNotEmpty()
                .anyMatch(c -> c.getId().equals("simple-test.AppComponent.ngOnInit"))
                .anyMatch(c -> c.getId().equals("simple-test.AppComponent.ngOnInit.jsdoc"))
                .anyMatch(c -> c.getId().equals("simple-test.AppComponent.loadConfiguration"))
                .anyMatch(c -> c.getId().equals("simple-test.AppComponent.loadConfiguration.jsdoc"))
                .anyMatch(c -> c.getId().equals("simple-test.AppComponent.toggleSidenav"))
                .anyMatch(c -> c.getId().equals("simple-test.AppComponent.toggleSidenav.jsdoc"))
                .anyMatch(c -> c.getId().equals("simple-test.AppComponent"))
        ;
        String classFrame = PxChunkTestUtil.combine(chunkList, c->hasCodeSection(c,"classFrame"));
        Assertions.assertThat(classFrame)
                .contains("export class AppComponent implements OnInit {")
                .contains("private titleService = inject(Title);")
                .contains("public readonly sidenavState$ = new BehaviorSubject<'opened' | 'closed'>('opened');")
                .contains("gOnInit(): void;")
                .contains("private loadConfiguration(): void;")
                .contains("public toggleSidenav(): void;")
                .endsWith("}\n")
                ;
    }

    @Test
    public void testRealExample() throws Exception {
        List<PxChunk> chunkList = chunkTypeScriptCode("/ts/benutzer-data-service.ts");
        Assertions.assertThat(chunkList)
                .isNotEmpty()
                .anyMatch(c -> hasCodeSection(c,"imports"))
                .anyMatch(c -> hasCodeSection(c,"method"))
                .anyMatch(c -> hasCodeSection(c,"classFrame"))
                .anyMatch( c -> hasIDContaining(c, "resetApiStatus"))
                ;
        String classFrame = PxChunkTestUtil.combine(chunkList, c->hasCodeSection(c,"classFrame"));
        Assertions.assertThat(classFrame)
                .contains("public getRegionalbereiche(): SelectItem[];")
        ;

        String methodImpl = PxChunkTestUtil.combine(chunkList, c -> hasCodeSection(c,"method") && hasIDContaining(c, "resetApiStatus"));
        Assertions.assertThat(methodImpl)
                .contains("public resetApiStatus():void {")
                .contains("this.apiStatusSubject.next(null);")
                .contains("this.updateBlockedStatusApiSubject.next(null);")
                .endsWith("}\n")
                ;

        methodImpl = PxChunkTestUtil.combine(chunkList, c -> hasCodeSection(c,"method") && hasIDContaining(c, "updateUserBlockedStatus"));
        Assertions.assertThat(methodImpl)
                .contains("public updateUserBlockedStatus(benutzer: Benutzer) {")
                .contains("this.httpClient.post(this.URL_UPDATE_BENUTZER_BLOCKED, benutzer).subscribe((response: Benutzer) => {")
                .contains("}, err => {")
                .endsWith("}\n")
        ;
    }

    @Test
    public void testGlobalFunction() throws Exception {

    }

    private boolean hasCodeSection(PxChunk c, String codeSectionName) {
        return codeSectionName.equals(c.getMetadata().get(TypeScriptCodeChunker.MDKEY_CODESECTION));
    }

    private boolean hasIDContaining(PxChunk c, String idSubString) {
        return c.getId().contains(idSubString);
    }

    private @NonNull List<PxChunk> chunkTypeScriptCode(String name) {
        String srcFileName = getClass().getResource(name).getFile();
        Stream<PxChunk> chunks = uut.chunk(new File(srcFileName));
        List<PxChunk> chunkList = chunks.toList();
        return chunkList;
    }

    private @NonNull List<PxChunk> chhunkTypeScriptString(String code) {
        File tmpFile = new File("src/test/tmp");
    }
}
