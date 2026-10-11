package ee.schimke.composeai.uibuilder.export

import kotlin.random.Random

/**
 * The name a new design gets before anyone types one: a mildly disreputable but meeting-safe
 * adjective–noun pair (`shady-raccoon`), memorable and always a valid id. The create route still
 * refuses an existing id. Shared by the browser's New design dialog and the server's create and
 * duplicate forms.
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
