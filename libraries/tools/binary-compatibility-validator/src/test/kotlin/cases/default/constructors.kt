/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package cases.default

class ClassConstructors
internal constructor(name: String, flags: Int = 0) {

    internal constructor(name: StringBuilder, flags: Int = 0) : this(name.toString(), flags)

}

