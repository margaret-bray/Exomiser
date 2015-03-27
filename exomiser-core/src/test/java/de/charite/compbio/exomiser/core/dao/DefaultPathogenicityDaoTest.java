/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package de.charite.compbio.exomiser.core.dao;

import de.charite.compbio.exomiser.core.model.Variant;
import de.charite.compbio.exomiser.core.model.pathogenicity.CaddScore;
import de.charite.compbio.exomiser.core.model.pathogenicity.MutationTasterScore;
import de.charite.compbio.exomiser.core.model.pathogenicity.PathogenicityData;
import de.charite.compbio.exomiser.core.model.pathogenicity.PolyPhenScore;
import de.charite.compbio.exomiser.core.model.pathogenicity.SiftScore;
import de.charite.compbio.jannovar.annotation.VariantEffect;
import de.charite.compbio.jannovar.reference.GenomeChange;
import de.charite.compbio.jannovar.reference.GenomePosition;
import de.charite.compbio.jannovar.reference.HG19RefDictBuilder;
import de.charite.compbio.jannovar.reference.PositionType;
import de.charite.compbio.jannovar.reference.Strand;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

/**
 *
 * @author Jules Jacobsen <jules.jacobsen@sanger.ac.uk>
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = DaoTestConfig.class)
@Sql(scripts = {"file:src/test/resources/sql/create_pathogenicity.sql", "file:src/test/resources/sql/pathogenicityDaoTestData.sql"})
public class DefaultPathogenicityDaoTest {

    @Autowired
    private DefaultPathogenicityDao instance;

    private static final SiftScore SIFT_SCORE = new SiftScore(0f);
    private static final PolyPhenScore POLY_PHEN_SCORE = new PolyPhenScore(0.998f);
    private static final MutationTasterScore MUTATION_TASTER_SCORE = new MutationTasterScore(1.0f);
    private static final CaddScore CADD_SCORE = new CaddScore(23.7f);

    @Mock
    Variant nonMissenseVariant;
    @Mock
    Variant missenseVariantNotInDatabase;
    @Mock
    Variant missenseVariantInDatabase;
    @Mock
    Variant missenseVariantWithNullSift;
    @Mock
    Variant missenseVariantWithNullPolyPhen;
    @Mock
    Variant missenseVariantWithNullMutTaster;
    @Mock
    Variant missenseVariantWithNullCadd;
    @Mock
    Variant missenseVariantWithMultipleRows;

    private static final PathogenicityData NO_PATH_DATA = new PathogenicityData(null, null, null, null);

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        Mockito.when(nonMissenseVariant.getVariantEffect()).thenReturn(VariantEffect.DOWNSTREAM_GENE_VARIANT);

        setUpMockMissenseVariant(missenseVariantNotInDatabase, 0, 0, "T", "G");
        setUpMockMissenseVariant(missenseVariantInDatabase, 10, 123256215, "T", "G");

        setUpMockMissenseVariant(missenseVariantWithNullSift, 1, 1, "A", "T");
        setUpMockMissenseVariant(missenseVariantWithNullPolyPhen, 1, 2, "A", "T");
        setUpMockMissenseVariant(missenseVariantWithNullMutTaster, 1, 3, "A", "T");
        setUpMockMissenseVariant(missenseVariantWithNullCadd, 1, 4, "A", "T");

        setUpMockMissenseVariant(missenseVariantWithMultipleRows, 1, 5, "A", "T");

    }

    private void setUpMockMissenseVariant(Variant variant, int chr, int pos, String ref, String alt) {
        Mockito.when(variant.getVariantEffect()).thenReturn(VariantEffect.MISSENSE_VARIANT);
        Mockito.when(variant.getChromosome()).thenReturn(chr);
        Mockito.when(variant.getPosition()).thenReturn(pos);
        Mockito.when(variant.getRef()).thenReturn(ref);
        Mockito.when(variant.getAlt()).thenReturn(alt);
    }

    @Test
    public void testNonMissenseVariantReturnsAnEmptyPathogenicityData() {
        PathogenicityData result = instance.getPathogenicityData(nonMissenseVariant);

        assertThat(result, equalTo(NO_PATH_DATA));
        assertThat(result.hasPredictedScore(), is(false));
    }

    @Test
    public void testMissenseVariantReturnsAnEmptyPathogenicityDataWhenNotInDatabase() {
        PathogenicityData result = instance.getPathogenicityData(missenseVariantNotInDatabase);

        assertThat(result, equalTo(NO_PATH_DATA));
        assertThat(result.hasPredictedScore(), is(false));
    }

    @Test
    public void testMissenseVariantReturnsPathogenicityDataWhenInDatabase() {
        PathogenicityData result = instance.getPathogenicityData(missenseVariantInDatabase);
        PathogenicityData expected = new PathogenicityData(POLY_PHEN_SCORE, MUTATION_TASTER_SCORE, SIFT_SCORE, CADD_SCORE);
        assertThat(result, equalTo(expected));
    }

    @Test
    public void testMissenseVariantInDatabaseWithNullSift() {
        PathogenicityData result = instance.getPathogenicityData(missenseVariantWithNullSift);
        PathogenicityData expected = new PathogenicityData(POLY_PHEN_SCORE, MUTATION_TASTER_SCORE, null, CADD_SCORE);
        assertThat(result, equalTo(expected));
    }

    @Test
    public void testMissenseVariantInDatabaseWithNullCadd() {
        PathogenicityData result = instance.getPathogenicityData(missenseVariantWithNullCadd);
        PathogenicityData expected = new PathogenicityData(POLY_PHEN_SCORE, MUTATION_TASTER_SCORE, SIFT_SCORE, null);
        assertThat(result, equalTo(expected));
    }

    @Test
    public void testMissenseVariantInDatabaseWithNullPolyPhen() {
        PathogenicityData result = instance.getPathogenicityData(missenseVariantWithNullPolyPhen);
        PathogenicityData expected = new PathogenicityData(null, MUTATION_TASTER_SCORE, SIFT_SCORE, CADD_SCORE);
        assertThat(result, equalTo(expected));
    }

    @Test
    public void testMissenseVariantInDatabaseWithNullMutTaster() {
        PathogenicityData result = instance.getPathogenicityData(missenseVariantWithNullMutTaster);
        PathogenicityData expected = new PathogenicityData(POLY_PHEN_SCORE, null, SIFT_SCORE, CADD_SCORE);
        assertThat(result, equalTo(expected));
    }

    @Test
    public void testMissenseVariantWithMultipleRowsReturnsBestScores() {
        PathogenicityData result = instance.getPathogenicityData(missenseVariantWithMultipleRows);
        PathogenicityData expected = new PathogenicityData(POLY_PHEN_SCORE, MUTATION_TASTER_SCORE, SIFT_SCORE, CADD_SCORE);
        assertThat(result, equalTo(expected));
    }
}
