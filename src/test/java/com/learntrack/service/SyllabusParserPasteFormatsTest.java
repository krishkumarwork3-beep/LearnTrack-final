package com.learntrack.service;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * Real-world paste formats that used to break the "Paste Syllabus" dialog:
 *  - EEE (UES013): "Unit 1: Title:" + Symbol/Wingdings bullets that paste as a box character
 *  - Physics: units are blank-line separated paragraphs with inner sub-headings
 *    (Acoustics:, Ultrasonics:, Interference: ...) that must NOT become units.
 */
public class SyllabusParserPasteFormatsTest {

    private static final String B = "\uF0B7"; // the "box" bullet that Word/PDF paste

    private static final String EEE =
            "Unit 1: DC Circuits:\n" +
            B + " Introduction to circuit elements; independent and dependent current and voltage\n" +
            "sources;\n" +
            B + " Kirchhoff\u2019s laws; mesh and node analysis; source transformations;\n" +
            B + " network theorems: Superposition theorem, Thevenin\u2019s and Norton\u2019s theorem,\n" +
            "Maximum power transfer theorem;\n" +
            B + " star-delta transformation;\n" +
            B + " transient response of R-L and R-C circuits.\n" +
            "Unit 2: AC Circuits & Magnetic circuits:\n" +
            B + " Concept of phasor, phasor representation of circuit elements;\n" +
            B + " balanced three phase circuits: voltage, current and power relations for star and delta\n" +
            "arrangement;\n" +
            B + " single-phase transformer.\n" +
            "Unit 3: Digital Logic Design:\n" +
            B + " Algebraic simplification, Sum of products and product of sums formulations (SOP\n" +
            "and POS),\n" +
            B + " Adder, Decoder, MUX.\n" +
            "Unit 4: Electronic Devices & Operational Amplifier Circuits:\n" +
            B + " p-n junction diode: V-I characteristics of diode,\n" +
            B + " Op-amp as a summing amplifier.\n" +
            "Laboratory Work: Kirchhoff\u2019s laws, network theorems, ac series and parallel circuit\n" +
            "Approved in 1st meeting of Academic Council held on September 11, 2025.";

    private static final String PHYSICS =
            "Syllabus\n\n" +
            "Oscillations and Waves: Oscillatory motion and damping, Applications -\n" +
            "Electromagnetic damping \u2013 eddy current; Acoustics: Reverberation time, absorption\n" +
            "coefficient, Sabine\u2019s and Eyring\u2019s formulae (Qualitative idea), Applications - Designing\n" +
            "of hall for speech, concert, and opera; Ultrasonics: Production and Detection of\n" +
            "Ultrasonic waves, Applications - green energy, sound signaling, dispersion of fog, remote\n" +
            "sensing, Car\u2019s airbag sensor.\n\n" +
            "Electromagnetic Waves: Scalar and vector fields; Gradient, divergence, and curl;\n" +
            "Stokes\u2019 and Green\u2019s theorems; Concept of Displacement current; Maxwell\u2019s equations;\n" +
            "Electromagnetic wave equations in free space and conducting media, Application - skin\n" +
            "depth.\n\n" +
            "Optics: Interference: Parallel and wedge-shaped thin films, Newton rings, Applications\n" +
            "as Non-reflecting coatings, Measurement of wavelength and refractive index.\n" +
            "Diffraction: Single and Double slit diffraction, and Diffraction grating, Applications -\n" +
            "Dispersive and Resolving Powers. Polarization: Production, detection, Applications \u2013\n" +
            "Anti-glare automobile headlights, Adjustable tint windows. Lasers: Basic concepts, Laser\n" +
            "properties, Ruby, HeNe, and Semiconductor lasers, Applications \u2013 Optical\n" +
            "communication and Optical alignment.\n\n" +
            "Quantum Mechanics: Wave function, Steady State Schrodinger wave equation,\n" +
            "Expectation value, Infinite potential well, Tunneling effect (Qualitative idea), Application\n" +
            "- Quantum computing.";

    @Test
    void eeeGlyphBulletsAreStrippedAndUnitsAreNumbered() {
        List<ParsedUnit> u = SyllabusParser.parseUnitList(EEE);

        assertEquals(4, u.size());
        assertEquals("Unit 1. DC Circuits", u.get(0).getName());
        assertEquals("Unit 2. AC Circuits & Magnetic circuits", u.get(1).getName());
        assertEquals("Unit 3. Digital Logic Design", u.get(2).getName());
        assertEquals("Unit 4. Electronic Devices & Operational Amplifier Circuits", u.get(3).getName());

        for (ParsedUnit unit : u) {
            for (String s : unit.getSubtopics()) {
                assertFalse(s.matches(".*[\\uE000-\\uF8FF\\uFFFD].*"), "glyph left in: " + s);
                assertFalse(s.endsWith(";") || s.endsWith(",") || s.endsWith("."), "dangling punctuation: " + s);
            }
        }

        List<String> u1 = u.get(0).getSubtopics();
        assertEquals(List.of(
                "Introduction to circuit elements",
                "independent and dependent current and voltage sources",
                "Kirchhoff\u2019s laws",
                "mesh and node analysis",
                "source transformations",
                "network theorems: Superposition theorem",
                "network theorems: Thevenin\u2019s and Norton\u2019s theorem",
                "network theorems: Maximum power transfer theorem",
                "star-delta transformation",
                "transient response of R-L and R-C circuits"), u1);

        // wrapped bullet text is rejoined, label keeps its meaning
        assertTrue(u.get(1).getSubtopics().contains(
                "balanced three phase circuits: current and power relations for star and delta arrangement"));
        // parentheses survive a wrapped line
        assertTrue(u.get(2).getSubtopics().contains("Sum of products and product of sums formulations (SOP and POS)"));
        // single-item "label: value" stays whole
        assertTrue(u.get(3).getSubtopics().contains("p-n junction diode: V-I characteristics of diode"));
        // Laboratory Work / Approved-in footer is not syllabus content
        assertEquals("Op-amp as a summing amplifier", u.get(3).getSubtopics().get(u.get(3).getSubtopics().size() - 1));
    }

    @Test
    void physicsParagraphsGiveFourUnitsWithInnerHeadingsKept() {
        List<ParsedUnit> u = SyllabusParser.parseUnitList(PHYSICS);

        assertEquals(List.of("Oscillations and Waves", "Electromagnetic Waves", "Optics", "Quantum Mechanics"),
                u.stream().map(ParsedUnit::getName).toList());

        List<String> osc = u.get(0).getSubtopics();
        assertTrue(osc.contains("Oscillatory motion and damping"));
        assertTrue(osc.contains("Applications - Electromagnetic damping \u2013 eddy current"));
        assertFalse(osc.contains("Applications -"), "orphan 'Applications -' must not appear");
        assertTrue(osc.contains("Acoustics: Reverberation time"));
        assertTrue(osc.contains("Acoustics: Sabine\u2019s and Eyring\u2019s formulae (Qualitative idea)"));
        assertTrue(osc.contains("Acoustics: opera"), "'and opera' -> 'opera'");
        assertTrue(osc.contains("Ultrasonics: Production and Detection of Ultrasonic waves"));
        assertTrue(osc.contains("Ultrasonics: Car\u2019s airbag sensor"));

        List<String> em = u.get(1).getSubtopics();
        assertEquals(9, em.size());
        assertTrue(em.contains("curl"));
        assertTrue(em.contains("Application - skin depth"));

        List<String> optics = u.get(2).getSubtopics();
        assertTrue(optics.contains("Interference: Newton rings"));
        assertTrue(optics.contains("Diffraction: Single and Double slit diffraction"));
        assertTrue(optics.contains("Diffraction: Diffraction grating"));
        assertTrue(optics.contains("Polarization: Production"));
        assertTrue(optics.contains("Lasers: Semiconductor lasers"));
        assertTrue(optics.contains("Interference: Applications as Non-reflecting coatings"));

        List<String> qm = u.get(3).getSubtopics();
        assertTrue(qm.contains("Tunneling effect (Qualitative idea)"));
        assertTrue(qm.contains("Application - Quantum computing"));
    }

    @Test
    void physicsWithoutBlankLinesStillSplitsIntoUnitsAtParagraphEnds() {
        // PDF copy that lost the blank lines between paragraphs
        String noBlanks = PHYSICS.replace("\n\n", "\n");
        List<ParsedUnit> u = SyllabusParser.parseUnitList(noBlanks);
        assertTrue(u.stream().anyMatch(x -> x.getName().equals("Oscillations and Waves")));
        assertTrue(u.stream().anyMatch(x -> x.getName().equals("Quantum Mechanics")));
        assertTrue(u.stream().noneMatch(x -> x.getName().contains(";")),
                "a wrapped line containing ';' must never become a unit name");
        assertTrue(u.stream().noneMatch(x -> x.getName().startsWith("Electromagnetic damping")));
    }

    @Test
    void unitVectorIsNotRomanNumeralFive() {
        List<ParsedUnit> u = SyllabusParser.parseUnitList("Unit Vector Analysis: gradient, curl\nUnit Testing: JUnit, Mockito");
        assertEquals(2, u.size());
        assertEquals("Unit Vector Analysis", u.get(0).getName());
    }

    @Test
    void romanNumeralUnitsStillWork() {
        List<ParsedUnit> u = SyllabusParser.parseUnitList(
                "Unit-I: Limits and Continuity:\nLimits; continuity\nUnit-II: Derivatives:\nDerivatives, Integrals");
        assertEquals(2, u.size());
        assertEquals("Unit I. Limits and Continuity", u.get(0).getName());
        assertEquals(List.of("Limits", "continuity"), u.get(0).getSubtopics());
        assertEquals("Unit II. Derivatives", u.get(1).getName());
        assertEquals(List.of("Derivatives", "Integrals"), u.get(1).getSubtopics());
    }

    @Test
    void bulletOnItsOwnLineIsAttachedToNextLine() {
        String t = "Unit 1: Basics:\n" + B + "\nOhm\u2019s law, power;\n" + B + "\nKVL";
        List<ParsedUnit> u = SyllabusParser.parseUnitList(t);
        assertEquals(List.of("Ohm\u2019s law", "power", "KVL"), u.get(0).getSubtopics());
    }

    private static final String CONTROL =
            "Syllabus\n" +
            "Mathematical Models, Block Diagrams and Signal Flow Graphs of Systems: Introduction\n" +
            "of mathematical models and transfer function, construction and reduction of block diagram\n" +
            "and signal flow graphs, application of Mason's gain formula.\n" +
            "Time-Domain Analysis of Control Systems: Transient and steady state response, time\n" +
            "response of first and second-order systems, steady-state errors, types of systems and error\n" +
            "constants.\n" +
            "System Stability: Conditions for stability of linear systems, algebraic stability criteria, Routh\n" +
            "criterion, root locus techniques, frequency domain analysis, stability analysis of the control\n" +
            "systems using various methods.\n" +
            "State Variable Analysis: Introduction, state variable representation, conversion of transfer\n" +
            "function model to state variable model, conversion of state variable model to transfer function\n" +
            "model.\n" +
            "Laboratory Work:\n";

    private static final String PSYCH =
            "UNIT I: PSYCHOLOGICAL PERSPECTIVE\n" +
            "Introduction to Psychology: Historical Background, Psychology as a science. Different\n" +
            "perspectives in Psychology.\n" +
            "Perception and Learning: Determinants of perception, Learning theories, Behavior\n" +
            "Modification.\n" +
            "Motivational and Affective basis of Behaviour: Basic Motives and their applications at work.\n" +
            "Components of emotions, Cognition and Emotion. Emotional Intelligence.\n" +
            "Group Dynamics and Interpersonal relationships.\n" +
            "Development of self and personality.\n" +
            "Transactional Analysis.\n" +
            "Culture and Mind.\n" +
            "Laboratory work:\n" +
            "1. Experiments on learning and behaviour modification.\n" +
            "2. Application of Motivation Theories: Need based assessment.\n" +
            "3. Experiments on understanding Emotions and their expressions.\n" +
            "4. Personality Assessment.\n" +
            "5. Exercises on Transactional analysis.\n" +
            "6. Role plays, case studies, simulation tests on human behaviour.\n" +
            "\n" +
            "UNIT II: HUMAN VALUES AND ETHICAL PERSPECTIVE\n" +
            "Values: Introduction to Values, Allport-Vernon Study of Values, Rokeach Value Survey,\n" +
            "Instrumental and Terminal Values.\n" +
            "Value Spectrum for a Good Life: Role of Different Types of Values such as Individual,\n" +
            "Societal, Material, Spiritual, Moral, and Psychological in living a good life.\n" +
            "Moral and Ethical Values: Types of Morality, Kant's Principles of Morality, Factors for taking\n" +
            "ethical decisions, Kohlberg's Theory of Moral Development.\n" +
            "Analyzing Individual human values such as Creativity, Freedom, Wisdom, Love and Trust.\n" +
            "Professional Ethics and Professional Ethos, Codes of Conduct, Whistle-blowing, Corporate\n" +
            "Social Responsibility.\n" +
            "Laboratory Work:\n" +
            "Practical application of these concepts by means of Discussions, Role-plays and Presentations,\n" +
            "Analysis of Case studies on ethics in business and CSR.\n" +
            "\n" +
            "UNIT III: ECONOMIC PERSPECTIVE\n" +
            "Basics of Demand and Supply\n" +
            "Production and cost analysis\n" +
            "\n" +
            "Approved in 1st meeting of Academic Council held on September 11, 2025. Revised in 3rd\n" +
            "meeting of Academic Council held on March 18, 2026.\n" +
            "\n" +
            "Market Structure: Perfect and Imperfect Markets.\n" +
            "Investment Decisions: capital Budgeting, Methods of Project Appraisal.\n" +
            "Macroeconomic Issues: Gross domestic product (GDP), Inflation and Financial Markets.\n" +
            "Globalisation: Meaning, General Agreement on Trade and tariffs (GATT), World Trade\n" +
            "Organisation (WTO). Global Liberalisation and its impact on Indian Economy.\n" +
            "Laboratory Work:\n";

    private static final String ANTENNA =
            "Syllabus\n" +
            "Introduction to Basic Antenna parameters: Radiation pattern, Radiation intensity, Beam\n" +
            "width, Gain, Directivity, Polarization, Bandwidth, Efficiency, Side lobes, Side lobe level,\n" +
            "Antenna Vector Effective Length and Equivalent Areas, Maximum Directivity and Maximum\n" +
            "Effective Area, Friss Transmission Equation.\n" +
            "Radiation Integrals and Auxiliary Potential Functions: Retarded vector and scalar potential,\n" +
            "Vector Potential A for an Electric Current Source J, Vector Potential F for a Magnetic Current\n" +
            "Source M, Electric and Magnetic Fields for Electric (J) and Magnetic (M) Current Sources.\n" +
            "Linear Wire Antennas: Radiation from an infinitesimal small current element, Radiation from\n" +
            "an elementary dipole (Hertzian dipole), Small Dipole, Finite length dipole, half wave dipole.\n" +
            "Antenna Arrays: Two-Element Array, Broadside arrays, End fire arrays. N-Element Linear\n" +
            "Array: Uniform Amplitude and Spacing, N-Element Linear Array: Directivity, N-Element\n" +
            "Linear Array: Uniform Spacing, Non uniform Amplitude, Binomial Array, Chebyshev Arrays,\n" +
            "Principle of pattern multiplication.\n" +
            "Propagation of Radio Waves: Different modes of propagation: Ground waves, Space waves,\n" +
            "Space wave propagation over flat and curved earth, Wave propagation in the Ionosphere,\n" +
            "Critical frequency, Maximum usable frequency (MUF), Skip distance, Virtual height.\n" +
            "Familiarization with Standards: IEEE 145-2013, IEEE 1502-2007 and IEEE 211-1997\n" +
            "Laboratory Work: Drive antenna by voltage, Radiation pattern of half wave dipole, Radiation\n";

    private static final String VERBAL =
            "" + B + " Verbal Ability & Reading Comprehension: Introduction to Reading Comprehension:\n" +
            "Strategies for approaching different types of passages, identifying main ideas, and\n" +
            "understanding structure, Advanced reading techniques, dealing with inferences, tone, and\n" +
            "author's purpose, Introduction to Verbal Ability: Grammar Fundamentals, sentence structure,\n" +
            "common errors, Sentence Correction and Completion, Review and Test-Taking Strategies:\n" +
            "Consolidate knowledge from all areas, focusing on test-taking strategies, time management,\n" +
            "and practice tests.\n" +
            "" + B + " Quantitative & Logical Reasoning: Introduction to Quantitative Ability - Arithmetic Basics:\n" +
            "arithmetic concepts (numbers, operations, percentages, ratios, averages), Advanced Arithmetic\n" +
            "& Introduction to Algebra: more complex arithmetic (interest, partnerships, mixtures, ratios,\n" +
            "variation) and basics of algebra (equations, inequalities, speed, time and distance), Algebra\n" +
            "Continued & Introduction to Modern Math: quadratic equations, sequences and introduce\n" +
            "concepts of modern math set theory, probability, permutations, combinations, and more\n" +
            "complex probability problems, Introduction to Data Interpretation: Basics of data\n" +
            "interpretation, understanding and interpreting bar charts and pie charts, Data Interpretation -\n" +
            "Line Charts and Tables, interpreting trends, and comparing data, practice session with mixed\n" +
            "problems from quantitative ability and data interpretation, Introduction to Logical Reasoning:\n" +
            "Number Series and Sequences, Family Relationships and Directions.\n";


    @Test
    void controlSystemsKeepsFirstUnitWithCommasInTitle() {
        List<ParsedUnit> u = SyllabusParser.parseUnitList(CONTROL);
        assertEquals(4, u.size());
        assertEquals("Mathematical Models, Block Diagrams and Signal Flow Graphs of Systems", u.get(0).getName());
        assertEquals(3, u.get(0).getSubtopics().size());
        assertEquals("Time-Domain Analysis of Control Systems", u.get(1).getName());
        assertEquals("State Variable Analysis", u.get(3).getName());
    }

    @Test
    void psychologyKeepsAllUnitsAndSkipsLabAndApprovedFooter() {
        List<ParsedUnit> u = SyllabusParser.parseUnitList(PSYCH);
        assertEquals(3, u.size());
        assertTrue(u.get(0).getName().startsWith("Unit I."));
        assertTrue(u.get(1).getName().startsWith("Unit II."));
        assertTrue(u.get(2).getName().startsWith("Unit III."));
        // hard-wrapped "Behavior" / "Modification." is one item
        assertTrue(u.get(0).getSubtopics().contains("Perception and Learning: Behavior Modification"));
        assertFalse(u.get(0).getSubtopics().contains("Modification"));
        // lab-work lines are not subtopics
        for (ParsedUnit unit : u) {
            for (String t : unit.getSubtopics()) {
                assertFalse(t.startsWith("Experiments on"), t);
                assertFalse(t.contains("Practical application"), t);
                assertFalse(t.contains("Academic Council"), t);
            }
        }
        // unit III continues after the page-break footer
        assertTrue(u.get(2).getSubtopics().contains("Market Structure: Perfect and Imperfect Markets"));
        assertTrue(u.get(2).getSubtopics().contains("Global Liberalisation and its impact on Indian Economy"));
    }

    @Test
    void antennaWrappedHeadingFragmentsAreNotUnits() {
        for (String text : new String[]{ANTENNA, ANTENNA.replace(".\n", ".\n\n")}) {
            List<ParsedUnit> u = SyllabusParser.parseUnitList(text);
            assertEquals(List.of("Introduction to Basic Antenna parameters",
                    "Radiation Integrals and Auxiliary Potential Functions", "Linear Wire Antennas",
                    "Antenna Arrays", "Propagation of Radio Waves", "Familiarization with Standards"),
                    u.stream().map(ParsedUnit::getName).toList());
            List<String> arrays = u.get(3).getSubtopics();
            assertTrue(arrays.contains("Two-Element Array"));
            assertTrue(arrays.contains("N-Element Linear Array: Directivity"));
            assertTrue(arrays.stream().noneMatch(x -> x.contains("N-Element Linear Array: N-Element")),
                    "heading must not be doubled");
            assertTrue(u.get(5).getSubtopics().contains("IEEE 145-2013"));
        }
    }

    @Test
    void bulletLedUnitsWithCommaSeparatedInnerHeadings() {
        List<ParsedUnit> u = SyllabusParser.parseUnitList(VERBAL);
        assertEquals(2, u.size());
        assertEquals("Verbal Ability & Reading Comprehension", u.get(0).getName());
        assertEquals("Quantitative & Logical Reasoning", u.get(1).getName());
        List<String> q = u.get(1).getSubtopics();
        assertTrue(q.contains("Algebra Continued & Introduction to Modern Math: probability"));
        assertTrue(q.contains("Introduction to Logical Reasoning: Family Relationships and Directions"));
        assertTrue(u.get(0).getSubtopics().contains("Introduction to Verbal Ability: Grammar Fundamentals"));
    }
}