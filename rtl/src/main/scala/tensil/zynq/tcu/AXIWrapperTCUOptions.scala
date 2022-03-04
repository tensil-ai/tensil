package tensil.zynq.tcu

import tensil.tcu.TCUOptions
import tensil.axi

case class AXIWrapperTCUOptions(
    inner: TCUOptions = TCUOptions(),
    dramAxiConfig: axi.Config,
)
