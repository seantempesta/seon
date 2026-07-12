"""Shared (query, tools) inputs for the JAX-vs-MLX parity proof.

README examples + common tool-calling phrasings + Clojure-ish queries
(the distribution this port will be finetuned toward in B2). Parity is
greedy-token-exact regardless of output quality.
"""

WEATHER = ('[{"name":"get_weather","description":"Get current weather for a city.",'
           '"parameters":{"location":{"type":"string","description":"City name.","required":true}}}]')
EMAIL = ('[{"name":"send_email","description":"Send an email to a recipient.",'
         '"parameters":{"to":{"type":"string","description":"The recipient email address.","required":true},'
         '"body":{"type":"string","description":"The email body text.","required":true}}}]')
STOCK = ('[{"name":"get_stock_price","description":"Get the current stock price.",'
         '"parameters":{"symbol":{"type":"string","description":"Ticker symbol.","required":true}}}]')
LIGHTS = ('[{"name":"toggle_lights","description":"Toggle smart lights on or off.",'
          '"parameters":{"state":{"type":"string","description":"on or off.","required":true}}}]')
ALARM = ('[{"name":"set_alarm","description":"Set an alarm.",'
         '"parameters":{"time":{"type":"string","description":"Alarm time.","required":true}}}]')
TRANSLATE = ('[{"name":"translate","description":"Translate text to a target language.",'
             '"parameters":{"text":{"type":"string","description":"Text to translate.","required":true},'
             '"language":{"type":"string","description":"Target language.","required":true}}}]')
MUSIC = ('[{"name":"play_music","description":"Play music by genre or artist.",'
         '"parameters":{"genre":{"type":"string","description":"Music genre.","required":false}}}]')
REMINDER = ('[{"name":"create_reminder","description":"Create a reminder.",'
            '"parameters":{"text":{"type":"string","description":"Reminder text.","required":true},'
            '"time":{"type":"string","description":"When to remind.","required":true}}}]')
CURRENCY = ('[{"name":"convert_currency","description":"Convert an amount between currencies.",'
            '"parameters":{"amount":{"type":"number","description":"Amount.","required":true},'
            '"from":{"type":"string","description":"Source currency.","required":true},'
            '"to":{"type":"string","description":"Target currency.","required":true}}}]')
SCHEMA_REGISTER = ('[{"name":"schema_register","description":"Register a Malli attribute schema.",'
                   '"parameters":{"attr":{"type":"string","description":"Namespaced keyword.","required":true},'
                   '"type":{"type":"string","description":"Malli type.","required":true}}}]')
DB_QUERY = ('[{"name":"db_query","description":"Run a Datalog query against the db.",'
            '"parameters":{"query":{"type":"string","description":"Datalog query string.","required":true}}}]')
PLAN = ('[{"name":"my_plan_reconcile","description":"Reconcile the plan from markdown.",'
        '"parameters":{"markdown":{"type":"string","description":"Plan markdown.","required":true}}}]')

PARITY_INPUTS = [
    # needle README examples
    ("What is the weather in San Francisco?", WEATHER),
    ("Send an email to john@example.com saying hello", EMAIL),
    ("Get the current stock price of AAPL", STOCK),
    # common tool-calling phrasings
    ("What's the weather in Paris?", WEATHER),
    ("Turn off the lights", LIGHTS),
    ("Turn off the lights in the kitchen", f"[{WEATHER[1:-1]},{LIGHTS[1:-1]}]"),
    ("Set an alarm for 7am tomorrow", ALARM),
    ("Translate 'good morning' to French", TRANSLATE),
    ("Play some jazz music", MUSIC),
    ("Remind me to call mom at 5pm", REMINDER),
    ("Convert 100 USD to EUR", CURRENCY),
    ("Email sarah@work.com that the meeting moved to 3pm", EMAIL),
    ("How is TSLA doing today?", STOCK),
    ("What is the weather like in Ulaanbaatar right now?", WEATHER),
    # Clojure-ish queries (the B2 finetune direction)
    ("Register a schema for a plan step with a title string and a done boolean",
     SCHEMA_REGISTER),
    ("current-ns: my.kb — query all sources with rating greater than 3", DB_QUERY),
    ("(user/run-tests 'seon.foo-test)", "[]"),
    ("(defn add-note! [text] (db/transact! :seon [{:my.kb/id (rand-id) :my.kb/text text}]))",
     "[]"),
    ("Create a plan: parse the CSV, validate the rows, transact the facts", PLAN),
    ("warnings: 2 failed evals in seon.agent.loop — fix the arity error then rerun",
     "[]"),
]
