package io.github.basekt.schema

/** A made-up shop: priced items with stock, and orders that belong to customers. */
val shopSchema: Schema = schema {
    resource("customers") {
        string("email") { required(); pattern("[^@ ]+@[^@ ]+", "Must be an email address.") }
        hasMany("orders", "orders", foreignField = "customerId")
        permissions { read("staff"); write("staff") }
    }
    resource("products") {
        string("name") { required(); length(min = 1, max = 120) }
        decimal("price") { required(); min("0.01") }
        int("stock") { min(0) }
        bool("published")
        permissions { read(everyone); write("staff"); delete("admin") }
    }
    resource("orders") {
        belongsTo("customer", "customers") { required() }
        enum("status", listOf("open", "paid", "shipped")) { required() }
        instant("placedAt")
        permissions { read(authenticated); create(authenticated); update("staff") }
    }
}

/** A made-up library catalogue: books by authors, each with an ISBN and a lending count. */
val catalogueSchema: Schema = schema {
    resource("authors") {
        string("name") { required() }
        hasMany("books", "books", foreignField = "authorId")
        permissions { read(everyone); write("librarian") }
    }
    resource("books") {
        string("title") { required(); length(max = 300) }
        string("isbn") { custom("isbn") }
        belongsTo("author", "authors")
        long("loans") { min(0) }
        permissions { read(everyone); write("librarian") }
    }
}

/** Accepts 10- or 13-digit ISBNs, ignoring dashes. */
val isbnValidator: FieldValidator = FieldValidator { value ->
    val digits = (value as Value.Text).value.replace("-", "")
    if (digits.length == 10 || digits.length == 13) null else "An ISBN has 10 or 13 digits."
}
