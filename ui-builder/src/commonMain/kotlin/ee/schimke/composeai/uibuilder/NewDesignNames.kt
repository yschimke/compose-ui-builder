package ee.schimke.composeai.uibuilder

import kotlin.random.Random

/**
 * The name a new design gets before anyone types one.
 *
 * An empty Design ID field made every new design a form to fill in, and the ids people typed under
 * pressure (`test`, `test2`, `asdf`) told nobody anything a week later. A generated adjective–noun
 * pair is unique enough to not collide across a host's designs in practice (the create route still
 * refuses an id that exists, so a collision is a second click, not a lost design), memorable enough
 * to say aloud, and always a valid id.
 *
 * The words are chosen to be mildly disreputable — a `shady-raccoon` is more fun to open than a
 * `design-17` — and no further: every word here is fine to read out in a meeting.
 */
object NewDesignNames {
  val adjectives: List<String> =
    listOf(
      "cheeky",
      "shady",
      "sneaky",
      "rowdy",
      "feral",
      "unhinged",
      "sassy",
      "salty",
      "crusty",
      "dodgy",
      "wonky",
      "scrappy",
      "smug",
      "grumpy",
      "tipsy",
      "brazen",
      "shameless",
      "saucy",
      "flaky",
      "chaotic",
      "rogue",
      "sketchy",
      "moody",
      "cranky",
      "greasy",
      "naughty",
      "reckless",
      "petty",
      "spicy",
      "sleepy",
      "hangry",
      "clumsy",
    )

  val nouns: List<String> =
    listOf(
      "goblin",
      "gremlin",
      "raccoon",
      "bandit",
      "hooligan",
      "weasel",
      "pigeon",
      "rascal",
      "badger",
      "ferret",
      "goose",
      "hamster",
      "walrus",
      "potato",
      "pickle",
      "biscuit",
      "noodle",
      "muffin",
      "llama",
      "capybara",
      "wombat",
      "scoundrel",
      "menace",
      "heathen",
      "imp",
      "trickster",
      "nugget",
      "possum",
      "seagull",
      "trombone",
      "waffle",
      "gecko",
    )

  /**
   * A `cheeky-raccoon`: lower-case adjective, hyphen, lower-case noun. Always a valid design id.
   */
  fun random(random: Random = Random.Default): String =
    adjectives[random.nextInt(adjectives.size)] + "-" + nouns[random.nextInt(nouns.size)]
}
