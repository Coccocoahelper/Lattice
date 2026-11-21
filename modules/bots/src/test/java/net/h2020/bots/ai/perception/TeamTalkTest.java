package net.h2020.bots.ai.perception;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

/** The chat reader: what a teammate types, and what the bot is supposed to take from it. */
public class TeamTalkTest {

    private static void expect(String line, TeamTalk.Intent... want) {
        List<TeamTalk.Intent> got = TeamTalk.intents(line);
        for (TeamTalk.Intent w : want) assertTrue("'" + line + "' should carry " + w + " but was " + got, got.contains(w));
    }

    private static void expectNone(String line) {
        assertTrue("'" + line + "' should carry nothing but was " + TeamTalk.intents(line),
                TeamTalk.intents(line).isEmpty());
    }

    @Test public void plansNameAColour() {
        expect("rush blue", TeamTalk.Intent.RUSH);
        expect("we rush blue", TeamTalk.Intent.RUSH);
        expect("blue next", TeamTalk.Intent.RUSH);
        expect("rush the red team", TeamTalk.Intent.RUSH);
        assertEquals("blue", TeamTalk.colourOf("rush blue"));
        assertEquals("red", TeamTalk.colourOf("rush the red team"));
        // the game spells it "gray"; people type "grey"
        assertEquals("gray", TeamTalk.colourOf("grey next"));
    }

    @Test public void aPlanCanCarryTwoThings() {
        List<TeamTalk.Intent> got = TeamTalk.intents("rush blue, i take bed");
        assertTrue(got.contains(TeamTalk.Intent.RUSH));
        assertTrue(got.contains(TeamTalk.Intent.BED));
        assertEquals("blue", TeamTalk.colourOf("rush blue, i take bed"));
    }

    @Test public void questionsAreNotPlans() {
        // A bot that answers "who we rushing" by starting a rush is worse than one that says nothing.
        expectNone("who we rushing");
        expectNone("where we going");
        expectNone("whats the plan");
        expectNone("which bed?");
    }

    @Test public void callouts() {
        expect("inc", TeamTalk.Intent.INC);
        expect("incoming", TeamTalk.Intent.INC);
        expect("rushing blue", TeamTalk.Intent.INC);
        expect("void", TeamTalk.Intent.VOID);
        expect("v", TeamTalk.Intent.VOID);
        expect("drop", TeamTalk.Intent.DROP);
        expect("split", TeamTalk.Intent.SPLIT);
        expect("ill def", TeamTalk.Intent.DEFEND);
        expect("gg", TeamTalk.Intent.GG);
        expect("glhf", TeamTalk.Intent.GL);
    }

    @Test public void bedClaimsNeedABed() {
        expect("i take bed", TeamTalk.Intent.BED);
        expect("cover me im going bed", TeamTalk.Intent.BED);
        // a bare "me" is not a plan
        expectNone("me");
    }

    @Test public void ordinaryChatIsNotACallout() {
        expectNone("lol");
        expectNone("hi");
        expectNone("this map is nice");
    }

    @Test public void incomingCarriesItsColour() {
        assertEquals("blue", TeamTalk.colourOf("incoming blue"));
        assertEquals("blue", TeamTalk.colourOf("rushing blue"));
    }
}
