package conduit.docs

import specular.ziotest.DocSpecSuite

object OverviewSuite extends DocSpecSuite:
  def doc = Overview.doc

object GettingStartedSuite extends DocSpecSuite:
  def doc = GettingStarted.doc

object MentalModelSuite extends DocSpecSuite:
  def doc = MentalModel.doc

object LensesAndOpticsSuite extends DocSpecSuite:
  def doc = LensesAndOptics.doc

object HandlersSuite extends DocSpecSuite:
  def doc = Handlers.doc

object ListenersSuite extends DocSpecSuite:
  def doc = Listeners.doc

object FastEqualitySuite extends DocSpecSuite:
  def doc = FastEquality.doc

object CollectionLensesSuite extends DocSpecSuite:
  def doc = CollectionLenses.doc

object IsoPageSuite extends DocSpecSuite:
  def doc = IsoPage.doc
