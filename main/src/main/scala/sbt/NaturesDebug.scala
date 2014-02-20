package sbt

	import Def.Setting
	import Natures._
	import NaturesDebug._

private[sbt] class NaturesDebug(val available: List[AutoPlugin], val nameToKey: Map[String, AttributeKey[_]], val provided: Relation[AutoPlugin, AttributeKey[_]])
{
	/** The set of [[AutoPlugin]]s that might define a key named `keyName`.
	* Because plugins can define keys in different scopes, this should only be used as a guideline. */
	def providers(keyName: String): Set[AutoPlugin] = nameToKey.get(keyName) match {
		case None => Set.empty
		case Some(key) => provided.reverse(key)
	}
	/** Describes alternative approaches for defining key [[keyName]] in [[context]].*/
	def toEnable(keyName: String, context: Context): List[PluginEnable] =
		providers(keyName).toList.map(plugin => pluginEnable(context, plugin))

	/** Provides text to suggest how [[notFoundKey]] can be defined in [[context]]. */
	def debug(notFoundKey: String, context: Context): String =
	{
		val (activated, deactivated) = Util.separate(toEnable(notFoundKey, context)) {
			case pa: PluginActivated => Left(pa)
			case pd: EnableDeactivated => Right(pd)
		}
		val activePrefix = if(activated.nonEmpty) s"Some already activated plugins define $notFoundKey: ${activated.mkString(", ")}\n" else ""
		activePrefix + debugDeactivated(notFoundKey, deactivated)
	}
	private[this] def debugDeactivated(notFoundKey: String, deactivated: Seq[EnableDeactivated]): String =
	{
		val (impossible, possible) = Util.separate(deactivated) {
			case pi: PluginImpossible => Left(pi)
			case pr: PluginRequirements => Right(pr)
		}
		if(possible.nonEmpty) {
			val explained = possible.map(explainPluginEnable)
			val possibleString =
				if(explained.size > 1) explained.zipWithIndex.map{case (s,i) => s"$i. $s"}.mkString("Multiple plugins are available that can provide $notFoundKey:\n", "\n", "")
				else s"$notFoundKey is provided by an available (but not activated) plugin:\n${explained.mkString}"
			def impossiblePlugins = impossible.map(_.plugin.label).mkString(", ")
			val imPostfix = if(impossible.isEmpty) "" else s"\n\nThere are other available plugins that provide $notFoundKey, but they are impossible to add: $impossiblePlugins"
			possibleString + imPostfix
		}
		else if(impossible.isEmpty)
			s"No available plugin provides key $notFoundKey."
		else {
			val explanations = impossible.map(explainPluginEnable)
			explanations.mkString(s"Plugins are available that could provide $notFoundKey, but they are impossible to add:\n\t", "\n\t", "")
		}
	}

	/** Text that suggests how to activate [[plugin]] in [[context]] if possible and if it is not already activated.*/
	def help(plugin: AutoPlugin, context: Context): String =
		if(context.enabled.contains(plugin))
			activatedHelp(plugin)
		else
			deactivatedHelp(plugin, context)
	private[this] def activatedHelp(plugin: AutoPlugin): String =
	{
		val prefix = s"${plugin.label} is activated."
		val keys = provided.forward(plugin)
		val keysString = if(keys.isEmpty) "" else s"\nIt may affect these keys: ${multi(keys.toList.map(_.label))}"
		val configs = plugin.projectConfigurations
		val confsString = if(configs.isEmpty) "" else s"\nIt defines these configurations: ${multi(configs.map(_.name))}"
		prefix + keysString + confsString
	}
	private[this] def deactivatedHelp(plugin: AutoPlugin, context: Context): String =
	{
		val prefix = s"${plugin.label} is not activated."
		val keys = provided.forward(plugin)
		val keysString = if(keys.isEmpty) "" else s"\nActivating it may affect these keys: ${multi(keys.toList.map(_.label))}"
		val configs = plugin.projectConfigurations
		val confsString = if(configs.isEmpty) "" else s"\nActivating it will define these configurations: ${multi(configs.map(_.name))}"
		val toActivate = explainPluginEnable(pluginEnable(context, plugin))
		s"$prefix$keysString$confsString\n$toActivate"
	}

	private[this] def multi(strs: Seq[String]): String = strs.mkString(if(strs.size > 4) "\n\t" else ", ")
}

private[sbt] object NaturesDebug
{
	/** Precomputes information for debugging natures and plugins. */
	def apply(available: List[AutoPlugin]): NaturesDebug =
	{
		val keyR = definedKeys(available)
		val nameToKey: Map[String, AttributeKey[_]] = keyR._2s.toList.map(key => (key.label, key)).toMap
		new NaturesDebug(available, nameToKey, keyR)
	}

	/** The context for debugging a plugin (de)activation.
	* @param initial The initially defined [[Nature]]s.
	* @param enabled The resulting model.
	* @param compile The function used to compute the model.
	* @param available All [[AutoPlugin]]s available for consideration. */
	final case class Context(initial: Natures, enabled: Seq[AutoPlugin], compile: Natures => Seq[AutoPlugin], available: List[AutoPlugin])

	/** Describes the steps to activate a plugin in some context. */
	sealed abstract class PluginEnable
	/** Describes a [[plugin]] that is already activated in the [[context]].*/
	final case class PluginActivated(plugin: AutoPlugin, context: Context) extends PluginEnable
	sealed abstract class EnableDeactivated extends PluginEnable
	/** Describes a [[plugin]] that cannot be activated in a [[context]] due to [[contradictions]] in requirements. */
	final case class PluginImpossible(plugin: AutoPlugin, context: Context, contradictions: Set[AutoPlugin]) extends EnableDeactivated

	/** Describes the requirements for activating [[plugin]] in [[context]].
	* @param context The base natures, exclusions, and ultimately activated plugins
	* @param blockingExcludes Existing exclusions that prevent [[plugin]] from being activated and must be dropped
	* @param enablingNatures [[Nature]]s that are not currently enabled, but need to be enabled for [[plugin]] to activate
	* @param extraEnabledPlugins Plugins that will be enabled as a result of [[plugin]] activating, but are not required for [[plugin]] to activate
	* @param willRemove Plugins that will be deactivated as a result of [[plugin]] activating
	* @param deactivate Describes plugins that must be deactivated for [[plugin]] to activate.  These require an explicit exclusion or dropping a transitive [[Nature]].*/
	final case class PluginRequirements(plugin: AutoPlugin, context: Context, blockingExcludes: Set[AutoPlugin], enablingNatures: Set[Nature], extraEnabledPlugins: Set[AutoPlugin], willRemove: Set[AutoPlugin], deactivate: List[DeactivatePlugin]) extends EnableDeactivated

	/** Describes a [[plugin]] that must be removed in order to activate another plugin in some context.
	* The [[plugin]] can always be directly, explicitly excluded.
	* @param removeOneOf If non-empty, removing one of these [[Nature]]s will deactivate [[plugin]] without affecting the other plugin.  If empty, a direct exclusion is required.
	* @param newlySelected If false, this plugin was selected in the original context.  */
	final case class DeactivatePlugin(plugin: AutoPlugin, removeOneOf: Set[Nature], newlySelected: Boolean)

	/** Determines how to enable [[plugin]] in [[context]]. */
	def pluginEnable(context: Context, plugin: AutoPlugin): PluginEnable =
		if(context.enabled.contains(plugin))
			PluginActivated(plugin, context)
		else
			enableDeactivated(context, plugin)

	private[this] def enableDeactivated(context: Context, plugin: AutoPlugin): PluginEnable =
	{
		// deconstruct the context
		val initialModel = context.enabled.toSet
		val initial = flatten(context.initial)
		val initialNatures = natures(initial)
		val initialExcludes = excludes(initial)

		val minModel = minimalModel(plugin)

		/* example 1
		A :- B, not C
		C :- D, E
		initial: B, D, E
		propose: drop D or E

		initial: B, not A
		propose: drop 'not A'

		example 2
		A :- B, not C
		C :- B
		initial: <empty>
		propose: B, exclude C
		*/

		// `plugin` will only be activated when all of these natures are activated
		// Deactivating any one of these would deactivate `plugin`.
		val minRequiredNatures = natures(minModel)

		// `plugin` will only be activated when all of these plugins are activated
		// Deactivating any one of these would deactivate `plugin`.
		val minRequiredPlugins = minModel.collect{ case a: AutoPlugin => a }.toSet

		// The presence of any one of these plugins would deactivate `plugin`
		val minAbsentPlugins = excludes(minModel).toSet

		// Plugins that must be both activated and deactivated for `plugin` to activate.
		//  A non-empty list here cannot be satisfied and is an error.
		val contradictions = minAbsentPlugins & minRequiredPlugins

		if(contradictions.nonEmpty)
			PluginImpossible(plugin, context, contradictions)
		else
		{
			// Natures that the user has to add to the currently selected natures in order to enable `plugin`.
			val addToExistingNatures = minRequiredNatures -- initialNatures

			// Plugins that are currently excluded that need to be allowed.
			val blockingExcludes = initialExcludes & minRequiredPlugins

			// The model that results when the minimal natures are enabled and the minimal plugins are excluded.
			//  This can include more plugins than just `minRequiredPlugins` because the natures required for `plugin`
			//  might activate other plugins as well.
			val modelForMin = context.compile(and(includeAll(minRequiredNatures), excludeAll(minAbsentPlugins)))

			val incrementalInputs = and( includeAll(minRequiredNatures ++ initialNatures), excludeAll(minAbsentPlugins ++ initialExcludes -- minRequiredPlugins))
			val incrementalModel = context.compile(incrementalInputs).toSet

			// Plugins that are newly enabled as a result of selecting the natures needed for `plugin`, but aren't strictly required for `plugin`.
			//   These could be excluded and `plugin` and the user's current plugins would still be activated.
			val extraPlugins = incrementalModel.toSet -- minRequiredPlugins -- initialModel

			// Plugins that will no longer be enabled as a result of enabling `plugin`.
			val willRemove = initialModel -- incrementalModel

			// Determine the plugins that must be independently deactivated.
			// If both A and B must be deactivated, but A transitively depends on B, deactivating B will deactivate A.
			// If A must be deactivated, but one if its (transitively) required natures isn't present, it won't be activated.
			//   So, in either of these cases, A doesn't need to be considered further and won't be included in this set.
			val minDeactivate = minAbsentPlugins.filter(p => Natures.satisfied(p.select, incrementalModel, natures(flatten(incrementalInputs))))

			val deactivate = for(d <- minDeactivate.toList) yield {
				// removing any one of these natures will deactivate `d`.  TODO: This is not an especially efficient implementation.
				val removeToDeactivate = natures(minimalModel(d)) -- minRequiredNatures
				val newlySelected = !initialModel(d)
				// a. suggest removing a nature in removeOneToDeactivate to deactivate d
				// b. suggest excluding `d` to directly deactivate it in any case
				// c. note whether d was already activated (in context.enabled) or is newly selected
				DeactivatePlugin(d, removeToDeactivate, newlySelected)
			}

			PluginRequirements(plugin, context, blockingExcludes, addToExistingNatures, extraPlugins, willRemove, deactivate)
		}
	}

	private[this] def includeAll[T <: Basic](basic: Set[T]): Natures = And(basic.toList)
	private[this] def excludeAll(plugins: Set[AutoPlugin]): Natures = And(plugins map (p => Exclude(p)) toList)

	private[this] def excludes(bs: Seq[Basic]): Set[AutoPlugin] = bs.collect { case Exclude(b) => b }.toSet
	private[this] def natures(bs: Seq[Basic]): Set[Nature] = bs.collect { case n: Nature => n }.toSet

	// If there is a model that includes `plugin`, it includes at least what is returned by this method.
	// This is the list of natures and plugins that must be included as well as list of plugins that must not be present.
	// It might not be valid, such as if there are contradictions or if there are cycles that are unsatisfiable.
	// The actual model might be larger, since other plugins might be enabled by the selected natures.
	private[this] def minimalModel(plugin: AutoPlugin): Seq[Basic] = Dag.topologicalSortUnchecked(plugin: Basic) {
		case _: Exclude | _: Nature => Nil
		case ap: AutoPlugin => Natures.flatten(ap.select)
	}

	/** String representation of [[PluginEnable]], intended for end users. */
	def explainPluginEnable(ps: PluginEnable): String =
		ps match {
			case PluginRequirements(plugin, context, blockingExcludes, enablingNatures, extraEnabledPlugins, toBeRemoved, deactivate) =>
				val parts =
					excludedError(false /* TODO */, blockingExcludes.toList) ::
					required(enablingNatures.toList) ::
					willAdd(plugin, extraEnabledPlugins.toList) ::
					willRemove(plugin, toBeRemoved.toList) ::
					needToDeactivate(deactivate) ::
					Nil
				parts.mkString("\n")
			case PluginImpossible(plugin, context, contradictions) => pluginImpossible(plugin, contradictions)
			case PluginActivated(plugin, context) => s"Plugin ${plugin.label} already activated."
		}

	/** Provides a [[Relation]] between plugins and the keys they potentially define.
	* Because plugins can define keys in different scopes and keys can be overridden, this is not definitive.*/
	def definedKeys(available: List[AutoPlugin]): Relation[AutoPlugin, AttributeKey[_]] =
	{
		def extractDefinedKeys(ss: Seq[Setting[_]]): Seq[AttributeKey[_]] =
			ss.map(_.key.key)
		def allSettings(p: AutoPlugin): Seq[Setting[_]] = p.projectSettings ++ p.buildSettings ++ p.globalSettings
		val empty = Relation.empty[AutoPlugin, AttributeKey[_]]
		(empty /: available)( (r,p) => r + (p, extractDefinedKeys(allSettings(p))) )
	}

	private[this] def excludedError(transitive: Boolean, dependencies: List[AutoPlugin]): String =
		str(dependencies)(excludedPluginError(transitive), excludedPluginsError(transitive))

	private[this] def excludedPluginError(transitive: Boolean)(dependency: AutoPlugin) =
		s"Required ${transitiveString(transitive)}dependency ${dependency.label} was excluded."
	private[this] def excludedPluginsError(transitive: Boolean)(dependencies: List[AutoPlugin]) =
		s"Required ${transitiveString(transitive)}dependencies were excluded:\n\t${labels(dependencies).mkString("\n\t")}"
	private[this] def transitiveString(transitive: Boolean) =
		if(transitive) "(transitive) " else ""

	private[this] def required(natures: List[Nature]): String =
		str(natures)(requiredNature, requiredNatures)

	private[this] def requiredNature(nature: Nature) =
		s"Required nature ${nature.label} not present."
	private[this] def requiredNatures(natures: List[Nature]) =
		s"Required natures not present:\n\t${natures.map(_.label).mkString("\n\t")}"

	private[this] def str[A](list: List[A])(f: A => String, fs: List[A] => String): String = list match {
		case Nil => ""
		case single :: Nil => f(single)
		case _ => fs(list)
	}

	private[this] def willAdd(base: AutoPlugin, plugins: List[AutoPlugin]): String =
		str(plugins)(willAddPlugin(base), willAddPlugins(base))

	private[this] def willAddPlugin(base: AutoPlugin)(plugin: AutoPlugin) =
		s"Enabling ${base.label} will also enable ${plugin.label}"
	private[this] def willAddPlugins(base: AutoPlugin)(plugins: List[AutoPlugin]) =
		s"Enabling ${base.label} will also enable:\n\t${labels(plugins).mkString("\n\t")}"

	private[this] def willRemove(base: AutoPlugin, plugins: List[AutoPlugin]): String =
		str(plugins)(willRemovePlugin(base), willRemovePlugins(base))

	private[this] def willRemovePlugin(base: AutoPlugin)(plugin: AutoPlugin) =
		s"Enabling ${base.label} will disable ${plugin.label}"
	private[this] def willRemovePlugins(base: AutoPlugin)(plugins: List[AutoPlugin]) =
		s"Enabling ${base.label} will disable:\n\t${labels(plugins).mkString("\n\t")}"

	private[this] def labels(plugins: List[AutoPlugin]): List[String] =
		plugins.map(_.label)

	private[this] def needToDeactivate(deactivate: List[DeactivatePlugin]): String =
		str(deactivate)(deactivate1, deactivateN)
	private[this] def deactivateN(plugins: List[DeactivatePlugin]): String =
		plugins.map(deactivate1).mkString("These plugins need to be deactivated:\n\t", "\n\t", "")
	private[this] def deactivate1(deactivate: DeactivatePlugin): String =
		s"Deactivate ${deactivateString(deactivate)}"
	private[this] def deactivateString(d: DeactivatePlugin): String =
	{
		val removeNaturesString: String =
			d.removeOneOf.toList match {
				case Nil => ""
				case x :: Nil => s"or no longer include $x"
				case xs => s"or remove one of ${xs.mkString(", ")}"
			}
		s"${d.plugin.label}: directly exclude it${removeNaturesString}"
	}

	private[this] def pluginImpossible(plugin: AutoPlugin, contradictions: Set[AutoPlugin]): String =
		str(contradictions.toList)(pluginImpossible1(plugin), pluginImpossibleN(plugin))

	private[this] def pluginImpossible1(plugin: AutoPlugin)(contradiction: AutoPlugin): String =
		s"There is no way to enable plugin ${plugin.label}.  It (or its dependencies) requires plugin ${contradiction.label} to both be present and absent.  Please report the problem to the plugin's author."
	private[this] def pluginImpossibleN(plugin: AutoPlugin)(contradictions: List[AutoPlugin]): String =
		s"There is no way to enable plugin ${plugin.label}.  It (or its dependencies) requires these plugins to be both present and absent:\n\t${labels(contradictions).mkString("\n\t")}\nPlease report the problem to the plugin's author."
}